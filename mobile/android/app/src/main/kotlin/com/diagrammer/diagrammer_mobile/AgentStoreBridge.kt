package com.diagrammer.diagrammer_mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** New application-private database only. All work and close run on one executor. */
class AgentStoreBridge(context: Context, messenger: BinaryMessenger) : MethodChannel.MethodCallHandler {
    private val channel = MethodChannel(messenger, "diagrammer/chat_store")
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val disposed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val helper = StoreHelper(context.applicationContext)
    init { channel.setMethodCallHandler(this) }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (disposed.get() || closed.get()) { result.error("store_closed", "Chat store is closed", null); return }
        val closing = call.method == "close"
        if (closing && !closed.compareAndSet(false, true)) { result.success(null); return }
        try {
            worker.execute {
                try {
                    val value = if (closing) { helper.close(); null } else execute(call)
                    main.post {
                        if (disposed.get()) result.error("store_closed", "Chat store was disposed", null)
                        else result.success(value)
                    }
                } catch (e: Exception) {
                    // Do not leak stored messages, keys, SQL or platform identities in errors.
                    main.post { result.error("store_rejected", "Chat store rejected the request", null) }
                }
            }
        } catch (e: RejectedExecutionException) { result.error("store_closed", "Chat store is closed", null) }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        closed.set(true)
        channel.setMethodCallHandler(null)
        worker.execute { helper.close() }
        worker.shutdown()
    }

    private fun execute(call: MethodCall): Any? {
        val args = call.arguments as? Map<*, *> ?: throw IllegalArgumentException("Arguments required")
        val a = JSONObject(args)
        require(bytes(canonical(a)) <= MAX_RECORD)
        val db = helper.writableDatabase
        return transaction(db) {
            when (call.method) {
                "saveConversation" -> {
                    val c = a.getJSONObject("conversation")
                    val scope = namespace(c.getJSONObject("scope")); val id = text(c, "id")
                    require(c.get("title") is String); instant(c, "createdAt"); instant(c, "updatedAt")
                    val old = read(db, "conversations", scope, id)
                    if (old != null) require(JSONObject(old).getString("createdAt") == c.getString("createdAt"))
                    put(db, "conversations", scope, id, c); null
                }
                "listConversations" -> list(db, "conversations", namespace(a.getJSONObject("scope")))
                    .sortedByDescending { JSONObject(it).getString("updatedAt") }
                "messages" -> {
                    val scope = namespace(a.getJSONObject("scope")); val id = text(a, "conversationId")
                    db.rawQuery("SELECT payload FROM messages WHERE scope=? AND conversation_id=? ORDER BY ordinal", arrayOf(scope, id))
                        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                }
                "appendMessage" -> {
                    append(db, namespace(a.getJSONObject("scope")), text(a, "conversationId"), a.getJSONObject("message")); null
                }
                "putCache" -> {
                    val e = a.getJSONObject("entry"); val scope = namespace(e.getJSONObject("scope")); val path = path(e)
                    instant(e, "fetchedAt"); require(e.get("complete") is Boolean); require(e.has("body"))
                    put(db, "cache", scope, path, e); null
                }
                "cache" -> {
                    val scope = namespace(a.getJSONObject("scope"))
                    if (a.has("path")) listOfNotNull(read(db, "cache", scope, path(a))) else list(db, "cache", scope)
                }
                "enqueue" -> {
                    val op = a.getJSONObject("operation"); val scope = namespace(op.getJSONObject("scope")); val id = text(op, "id")
                    val conversationId = text(op, "conversationId")
                    require(op.getString("method") == "POST" && op.getString("state") == "queued")
                    path(op); instant(op, "createdAt"); require(op.has("body"))
                    require(read(db, "conversations", scope, conversationId) != null)
                    val old = read(db, "outbox", scope, id)
                    val fingerprint = if (a.has("message")) MessageDigest.getInstance("SHA-256")
                        .digest(canonical(a.getJSONObject("message")).toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) } else null
                    if (old != null) {
                        require(operationIdentity(JSONObject(old)) == operationIdentity(op))
                        db.rawQuery("SELECT enqueue_message FROM outbox WHERE scope=? AND id=?", arrayOf(scope, id)).use {
                            require(it.moveToFirst() && (if (it.isNull(0)) null else it.getString(0)) == fingerprint)
                        }
                    }
                    if (a.has("message")) append(db, scope, conversationId, a.getJSONObject("message"))
                    if (old == null) put(db, "outbox", scope, id, op, conversationId, fingerprint)
                    null
                }
                "outbox" -> list(db, "outbox", namespace(a.getJSONObject("scope")))
                "updateOutbox" -> {
                    val scope = namespace(a.getJSONObject("scope")); val id = text(a, "id")
                    val old = JSONObject(read(db, "outbox", scope, id) ?: throw IllegalArgumentException("Unknown operation"))
                    val state = a.getString("state")
                    require(transition(old.getString("state"), state))
                    old.put("state", state); old.remove("error")
                    if (a.has("error")) { require(a.get("error") is String); old.put("error", a.getString("error")) }
                    put(db, "outbox", scope, id, old, text(old, "conversationId")); null
                }
                else -> throw IllegalArgumentException("Unsupported store operation")
            }
        }
    }

    private fun append(db: SQLiteDatabase, scope: String, conversationId: String, message: JSONObject) {
        require(read(db, "conversations", scope, conversationId) != null)
        val id = text(message, "id")
        require(message.getString("role") in setOf("user", "assistant", "tool", "system"))
        require(message.get("content") is String); instant(message, "createdAt")
        if (message.has("metadata")) require(message.get("metadata") is JSONObject)
        if (message.has("sequence")) {
            val sequence = message.get("sequence")
            require(sequence is Number && sequence.toLong() >= 0 && sequence.toDouble() == sequence.toLong().toDouble())
        }
        val old = read(db, "messages", scope, id)
        if (old != null) {
            require(old == canonical(message))
            db.rawQuery("SELECT conversation_id FROM messages WHERE scope=? AND id=?", arrayOf(scope, id)).use {
                require(it.moveToFirst() && it.getString(0) == conversationId)
            }
            return
        }
        put(db, "messages", scope, id, message, conversationId)
    }

    private fun read(db: SQLiteDatabase, table: String, scope: String, id: String): String? =
        db.rawQuery("SELECT payload FROM $table WHERE scope=? AND id=?", arrayOf(scope, id)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    private fun list(db: SQLiteDatabase, table: String, scope: String): List<String> =
        db.rawQuery("SELECT payload FROM $table WHERE scope=? ORDER BY ordinal", arrayOf(scope)).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
    private fun put(db: SQLiteDatabase, table: String, scope: String, id: String, payload: JSONObject, conversationId: String? = null, enqueueMessage: String? = null) {
        val json = canonical(payload); require(bytes(json) <= MAX_RECORD)
        val old = read(db, table, scope, id)
        var total = 0L; var rows = 0L
        for (name in TABLES) {
            db.rawQuery("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0), COUNT(*) FROM $name", null).use {
                it.moveToFirst(); total += it.getLong(0); rows += it.getLong(1)
            }
        }
        require(total - (old?.let { bytes(it) } ?: 0) + bytes(json) <= MAX_TOTAL)
        require(old != null || rows < MAX_ROWS)
        val values = ContentValues().apply {
            put("scope", scope); put("id", id); put("payload", json)
            if (conversationId != null) put("conversation_id", conversationId)
            if (table == "outbox" && old == null) put("enqueue_message", enqueueMessage)
        }
        if (old != null) db.update(table, values, "scope=? AND id=?", arrayOf(scope, id))
        else db.insertOrThrow(table, null, values)
    }
    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try { val value = block(); db.setTransactionSuccessful(); return value }
        finally { db.endTransaction() }
    }
    private fun text(json: JSONObject, key: String): String {
        val value = json.get(key); require(value is String && value.isNotBlank() && value.length <= 2048 && !value.contains('\u0000'))
        return value
    }
    private fun namespace(scope: JSONObject): String = canonical(JSONArray(listOf(
        text(scope, "platform"), text(scope, "userId"), text(scope, "projectId"), text(scope, "deploymentId"), text(scope, "contractHash")
    )))
    private fun path(json: JSONObject): String {
        val value = text(json, "path")
        require(value.startsWith("/") && !value.startsWith("//") && !value.contains('\\') && !value.contains('#'))
        return value
    }
    private fun instant(json: JSONObject, key: String) { Instant.parse(json.getString(key)) }
    private fun operationIdentity(json: JSONObject): String = canonical(JSONObject(json.toString()).apply { remove("state"); remove("error") })
    private fun transition(from: String, to: String): Boolean {
        require(to in setOf("queued", "sending", "completed", "rejected", "unknown", "conflict"))
        return from == to || when (from) {
            "queued" -> to in setOf("sending", "rejected", "conflict")
            "sending" -> to in setOf("completed", "rejected", "unknown", "conflict")
            else -> false
        }
    }
    private fun bytes(value: String): Int = value.toByteArray(Charsets.UTF_8).size
    private fun canonical(value: Any?, depth: Int = 0): String {
        require(depth <= 64)
        return when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it), depth + 1) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it), depth + 1) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> JSONObject.numberToString(value)
        else -> throw IllegalArgumentException("Invalid JSON")
        }
    }

    private class StoreHelper(context: Context) : SQLiteOpenHelper(context, "agent_chat_v1.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE conversations(ordinal INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(scope,id))")
            db.execSQL("CREATE TABLE cache(ordinal INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(scope,id))")
            for (table in listOf("messages", "outbox")) {
                val extra = if (table == "outbox") "enqueue_message TEXT," else ""
                db.execSQL("CREATE TABLE $table(ordinal INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, conversation_id TEXT NOT NULL, ${extra} UNIQUE(scope,id), FOREIGN KEY(scope,conversation_id) REFERENCES conversations(scope,id))")
            }
            db.execSQL("CREATE INDEX messages_conversation ON messages(scope,conversation_id,ordinal)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw IllegalStateException("No migration is supported")
        }
    }
    companion object {
        private const val MAX_RECORD = 1024 * 1024
        private const val MAX_TOTAL = 64L * 1024 * 1024
        private const val MAX_ROWS = 10000
        private val TABLES = listOf("conversations", "messages", "cache", "outbox")
    }
}


