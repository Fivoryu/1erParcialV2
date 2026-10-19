package com.diagrammer.diagrammer_mobile

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.google.gson.internal.LazilyParsedNumber
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** One proposal only. This mapper never authorizes or executes an operation. */
internal object AgentDecisionMapping {
    private val gson = GsonBuilder().serializeNulls().create()
    private val operators = setOf("sum", "add", "mean", "min", "max", "multiply", "subtract", "divide")

    fun decision(text: String, calls: List<ToolCall>): String {
        require(text.length <= 32768 && calls.size <= 1)
        val action = if (calls.isNotEmpty()) {
            require(text.isBlank()) { "Mixed decisions are unsupported." }
            val call = calls.single()
            @Suppress("UNCHECKED_CAST")
            val arguments = TrialChannelValues.normalize(call.arguments) as Map<String, Any?>
            val payload = when (call.name) {
                "answer", "clarify" -> {
                    exact(arguments, setOf("text"))
                    mapOf("tag" to call.name, "text" to arguments["text"])
                }
                "read", "delete_preview" -> {
                    exact(arguments, setOf("entity", "id"), if (call.name == "read") setOf("entity") else setOf("entity", "id"))
                    linkedMapOf<String, Any?>("tag" to call.name).apply { putAll(arguments) }
                }
                "create", "update" -> {
                    val keys = if (call.name == "create") setOf("entity", "values") else setOf("entity", "id", "values")
                    exact(arguments, keys)
                    val values = arguments["values"]
                    require(values is String) { "Values must be an encoded JSON object." }
                    linkedMapOf<String, Any?>("tag" to call.name).apply {
                        putAll(arguments)
                        put("values", objectJson(values))
                    }
                }
                "calculate" -> {
                    exact(arguments, setOf("op", "values"))
                    mapOf("tag" to "calculate", "calculation" to arguments)
                }
                else -> throw IllegalArgumentException("Unsupported decision tool.")
            }
            validateAction(payload)
        } else {
            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "Missing decision." }
            if (trimmed.startsWith("{")) {
                validateAction(objectJson(trimmed))
            } else {
                // Never disguise malformed JSON, fenced output or a wrapped action as prose.
                require(trimmed.none { it in "{}[]`\"" } && trimmed !in setOf("null", "true", "false"))
                require(!trimmed.matches(Regex("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")))
                require(trimmed.any { it.isLetter() }) { "Expected prose or one strict decision." }
                mapOf("tag" to "answer", "text" to text)
            }
        }
        val encoded = gson.toJson(TrialChannelValues.normalize(action))
        require(encoded.length <= 32768) { "Decision exceeds limits." }
        TrialChannelValues.validateEnvelope(encoded)
        return encoded
    }

    private fun validateAction(action: Map<String, Any?>): Map<String, Any?> {
        val tag = action["tag"]
        require(tag is String)
        when (tag) {
            "answer", "clarify" -> {
                exact(action, setOf("tag", "text"))
                boundedText(action["text"], 32768)
            }
            "read", "create", "update", "delete_preview" -> {
                val allowed = when (tag) {
                    "read" -> setOf("tag", "entity", "id")
                    "create" -> setOf("tag", "entity", "values")
                    "update" -> setOf("tag", "entity", "id", "values")
                    else -> setOf("tag", "entity", "id")
                }
                exact(action, allowed, if (tag == "read") setOf("tag", "entity") else allowed)
                boundedText(action["entity"], 32768)
                if (action.containsKey("id")) boundedText(action["id"], 32768)
                if (tag == "create" || tag == "update") require(action["values"] is Map<*, *>)
            }
            "calculate" -> {
                exact(action, setOf("tag", "calculation"))
                val calculation = action["calculation"]
                require(calculation is Map<*, *> && calculation.keys == setOf("op", "values"))
                require(calculation["op"] in operators)
                val values = calculation["values"]
                require(values is List<*> && values.isNotEmpty() && values.size <= 1000)
                require(values.all { it is Number && it.toDouble().isFinite() })
                if (calculation["op"] in setOf("subtract", "divide")) require(values.size == 2)
                if (calculation["op"] == "divide") require((values[1] as Number).toDouble() != 0.0)
            }
            else -> throw IllegalArgumentException("Unsupported decision tag.")
        }
        return action
    }

    private fun boundedText(value: Any?, maximum: Int) {
        require(value is String && value.isNotBlank() && value.length <= maximum)
    }

    private fun exact(value: Map<String, Any?>, allowed: Set<String>, required: Set<String> = allowed) {
        require(value.keys.all { it in allowed } && value.keys.containsAll(required))
    }

    /** Strict streaming parsing also rejects duplicate keys before a map could hide them. */
    private fun objectJson(text: String): Map<String, Any?> {
        require(text.length <= 32768)
        var nodes = 0
        var characters = 0
        fun bounded(text: String): String {
            characters += text.length
            require(text.length <= 32768 && characters <= 65536)
            return text
        }
        val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
        fun read(depth: Int): Any? {
            require(depth <= 16 && ++nodes <= 2048)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    val result = linkedMapOf<String, Any?>()
                    while (reader.hasNext()) {
                        val key = bounded(reader.nextName())
                        require(!result.containsKey(key)) { "Duplicate JSON key." }
                        result[key] = read(depth + 1)
                    }
                    reader.endObject()
                    result
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    val result = mutableListOf<Any?>()
                    while (reader.hasNext()) result.add(read(depth + 1))
                    reader.endArray()
                    result
                }
                JsonToken.STRING -> bounded(reader.nextString())
                JsonToken.NUMBER -> {
                    val token = reader.nextString()
                    require(token.length <= 128)
                    TrialChannelValues.normalize(LazilyParsedNumber(token))
                }
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> throw IllegalArgumentException("Malformed decision JSON.")
            }
        }
        try {
            val value = read(0)
            require(value is Map<*, *> && reader.peek() == JsonToken.END_DOCUMENT)
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        } catch (_: Exception) {
            throw IllegalArgumentException("Expected one strict JSON object.")
        } finally {
            reader.close()
        }
    }

    fun tools(): List<OpenApiTool> = listOf(
        definition("read", "Propose reading contract records; id is optional.", mapOf("entity" to "string", "id" to "string"), listOf("entity")),
        definition("create", "Propose creation; values must be a JSON object encoded as a string.", mapOf("entity" to "string", "values" to "string"), listOf("entity", "values")),
        definition("update", "Propose editing; values must be a JSON object encoded as a string.", mapOf("entity" to "string", "id" to "string", "values" to "string"), listOf("entity", "id", "values")),
        definition("delete_preview", "Propose a deletion preview, never execute deletion.", mapOf("entity" to "string", "id" to "string"), listOf("entity", "id")),
        definition("calculate", "Propose deterministic arithmetic on supplied finite values: sum, add, mean, min, max, multiply, subtract, divide.", mapOf("op" to "string", "values" to "array"), listOf("op", "values")),
        definition("clarify", "Ask for missing or ambiguous information.", mapOf("text" to "string"), listOf("text")),
        definition("answer", "Provide an answer grounded in the supplied history and observations.", mapOf("text" to "string"), listOf("text")),
    )

    private fun definition(name: String, description: String, fields: Map<String, String>, required: List<String>): OpenApiTool =
        object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = gson.toJson(mapOf(
                "name" to name,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to fields.mapValues { (_, type) ->
                        if (type == "array") mapOf("type" to type, "items" to mapOf("type" to "number"))
                        else mapOf("type" to type)
                    },
                    "required" to required,
                    "additionalProperties" to false,
                ),
            ))
            override fun execute(paramsJsonString: String): String =
                throw UnsupportedOperationException("Agent tool proposals must never execute natively.")
        }
}
