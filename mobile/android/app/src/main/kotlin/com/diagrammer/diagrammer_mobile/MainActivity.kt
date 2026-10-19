package com.diagrammer.diagrammer_mobile

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var mnn: MnnBridge? = null
    private var chatStore: AgentStoreBridge? = null
    private var liteRtTrial: AutoCloseable? = null
    private var liteRtAgent: AutoCloseable? = null
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        mnn = MnnBridge(this, flutterEngine.dartExecutor.binaryMessenger)
        chatStore = AgentStoreBridge(this, flutterEngine.dartExecutor.binaryMessenger)
        // The class and dependency are absent unless -PlitertTrial=true is selected.
        val trialClass = try {
            Class.forName("com.diagrammer.diagrammer_mobile.LiteRtTrialBridge")
        } catch (_: ClassNotFoundException) { null }
        liteRtTrial = trialClass?.getConstructor(
            android.content.Context::class.java,
            io.flutter.plugin.common.BinaryMessenger::class.java,
        )?.newInstance(this, flutterEngine.dartExecutor.binaryMessenger) as? AutoCloseable
        val agentClass = try {
            Class.forName("com.diagrammer.diagrammer_mobile.LiteRtAgentBridge")
        } catch (_: ClassNotFoundException) { null }
        liteRtAgent = agentClass?.getConstructor(
            android.content.Context::class.java,
            io.flutter.plugin.common.BinaryMessenger::class.java,
        )?.newInstance(this, flutterEngine.dartExecutor.binaryMessenger) as? AutoCloseable
    }
    override fun onDestroy() {
        liteRtAgent?.close()
        liteRtAgent = null
        liteRtTrial?.close()
        liteRtTrial = null
        chatStore?.dispose()
        chatStore = null
        mnn?.dispose()
        mnn = null
        super.onDestroy()
    }
}
