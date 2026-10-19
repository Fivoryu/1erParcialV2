package com.diagrammer.diagrammer_mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** One worker owns every native session call; Flutter and the UI thread never block. */
class MnnBridge(private val context: Context, messenger: BinaryMessenger) {
    private val channel = MethodChannel(messenger, "diagrammer/mnn")
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val disposed = AtomicBoolean(false)
    private var session = 0L
    private val libraryLoaded = try { System.loadLibrary("diagrammer_mnn"); true }
        catch (_: UnsatisfiedLinkError) { false }

    private external fun compiled(): Boolean
    private external fun load(configPath: String): Long
    private external fun generateNative(handle: Long, prompt: String, maxTokens: Int): String
    private external fun generateAgentNative(handle: Long, prompt: String, maxTokens: Int): String
    private external fun releaseNative(handle: Long)

    init {
        channel.setMethodCallHandler { call, result ->
            if (disposed.get()) {
                result.error("MNN_DISPOSED", "The local runtime has been released.", null)
            } else if (call.method !in setOf("discoverModel", "loadModel", "generate", "generateAgent", "release")) {
                result.notImplemented()
            } else {
                worker.execute {
                    try {
                        val value: Any? = when (call.method) {
                            "discoverModel" -> {
                                val required = listOf("llm_config.json", "tokenizer.txt", "llm.mnn", "llm.mnn.weight", "visual.mnn", "visual.mnn.weight")
                                listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
                                    .map { root -> Pair(root.canonicalFile, File(root, "models/qwen3.5-0.8b").canonicalFile) }
                                    .firstOrNull { (root, directory) ->
                                        directory.path.startsWith(root.path + File.separator) &&
                                            required.all { name ->
                                                val file = File(directory, name)
                                                file.isFile && file.length() > 0 && file.canonicalFile.parentFile == directory
                                            }
                                    }?.second?.absolutePath
                            }
                            "loadModel" -> {
                                check(libraryLoaded && compiled()) {
                                    "MNN is not included in this build. Build with DIAGRAMMER_MNN_ROOT set to MNN 3.6.1."
                                }
                                val config = validatedPackage(call.argument<String>("directory") ?: "")
                                if (session != 0L) { releaseNative(session); session = 0L }
                                session = load(config.absolutePath)
                                check(session != 0L) { "MNN could not load the verified local model." }
                                mapOf("loaded" to true, "message" to "Qwen3.5-0.8B • MNN 3.6.1 • device-local CPU")
                            }
                            "generate", "generateAgent" -> {
                                check(session != 0L) { "Load the local Qwen model before using the agent." }
                                val prompt = call.argument<String>("prompt") ?: ""
                                val tokens = call.argument<Int>("maxTokens") ?: 512
                                require(prompt.isNotBlank() && prompt.length <= 32768 && tokens in 1..512) {
                                    "The prompt or generation token limit is invalid."
                                }
                                if (call.method == "generateAgent") generateAgentNative(session, prompt, tokens) else generateNative(session, prompt, tokens)
                            }
                            else -> {
                                if (session != 0L) { releaseNative(session); session = 0L }
                                null
                            }
                        }
                        main.post { if (!disposed.get()) result.success(value) }
                    } catch (error: Exception) {
                        main.post { if (!disposed.get()) result.error("MNN_UNAVAILABLE", error.message ?: "Local inference failed.", null) }
                    } catch (_: LinkageError) {
                        main.post { if (!disposed.get()) result.error("MNN_LIBRARY_MISSING", "The native MNN library is unavailable for this device ABI.", null) }
                    }
                }
            }
        }
    }

    private fun validatedPackage(path: String): File {
        val directory = File(path).canonicalFile
        val roots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null)).map { it.canonicalFile }
        require(roots.any { directory.path.startsWith(it.path + File.separator) }) {
            "Place the model inside this app's files/models directory. Shared arbitrary storage is not allowed."
        }
        val required = listOf("llm_config.json", "tokenizer.txt", "llm.mnn", "llm.mnn.weight", "visual.mnn", "visual.mnn.weight")
        required.forEach { name ->
            val file = File(directory, name)
            require(file.isFile && file.length() > 0 && file.canonicalFile.parentFile == directory) {
                "The local model package is incomplete or contains an unsafe file reference."
            }
        }
        val hashes = mapOf(
            "llm_config.json" to "33f2c15bda1911ed666418437f45900e78185b57b883545e5d02c14f92a0907b",
            "tokenizer.txt" to "7e75de1f279a10b65bd9dc1a5207205cb8993823861c4c42bbbd74e48e1c23a4",
            "visual.mnn" to "05e92b95a1295ee747b0436061b7ae7b1d1d3c7903306fe99e4af13e5aca3de3",
            "visual.mnn.weight" to "4c03fcac0d164d014e5cd1a1d7ab4fc95babbec724a268f3ba486c5db3511161",
            "llm.mnn" to "94e0459e10584487a3bda77dc3c3322c0c01cfc9d223a7284e8d07345bf877d5",
            "llm.mnn.weight" to "4e25ef4cbfb49a33013d17300c2f68cd9c9ce8f2e3f7b947fd2c574ec297ca5e"
        )
        hashes.forEach { (name, expected) ->
            val digest = MessageDigest.getInstance("SHA-256")
            File(directory, name).inputStream().use { input ->
                val buffer = ByteArray(65536)
                var count = input.read(buffer)
                while (count > 0) { digest.update(buffer, 0, count); count = input.read(buffer) }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(actual == expected) { "Qwen3.5-0.8B model integrity verification failed." }
        }
        val llmFile = File(directory, "llm_config.json")
        require(llmFile.length() <= 65536) { "The local model configuration is too large." }
        val model = JSONObject(llmFile.readText())
        require(model.optString("model_type") == "qwen3_5" && model.optInt("hidden_size") == 1024) {
            "This model is not the supported Qwen3.5-0.8B package."
        }
        val reserved = setOf("base_dir", "llm_config", "llm_model", "llm_weight", "tokenizer_file", "visual_model", "audio_model", "context_file", "tmp_path", "prefix_cache_path")
        require(reserved.none { model.has(it) }) { "The model configuration overrides protected file paths." }
        // Do not trust the downloaded execution config's paths or network capabilities.
        val execution = JSONObject().apply {
            put("llm_model", "llm.mnn"); put("llm_weight", "llm.mnn.weight")
            put("llm_config", "llm_config.json"); put("tokenizer_file", "tokenizer.txt")
            put("visual_model", "visual.mnn"); put("backend_type", "cpu")
            put("thread_num", 2); put("memory", "low"); put("precision", "low")
            put("max_new_tokens", 512); put("max_all_tokens", 8192); put("timeout_ms", 120000)
            put("reuse_kv", false); put("prompt_cache", false)
            put("jinja", JSONObject().put("context", JSONObject().put("enable_thinking", false)))
        }
        return File(directory, "diagrammer_runtime.json").apply { writeText(execution.toString()) }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        channel.setMethodCallHandler(null)
        worker.execute { if (session != 0L) { releaseNative(session); session = 0L } }
        worker.shutdown()
    }
}