package com.diagrammer.diagrammer_mobile

import com.google.ai.edge.litertlm.ToolCall
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.flutter.plugin.common.StandardMethodCodec

/** Standalone mapping checks; no engine, gateway, database or device is constructed. */
fun main() {
    var checks = 0
    fun expect(label: String, condition: Boolean) {
        check(condition) { label }
        checks++
    }
    fun sdkArguments(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return Class.forName("com.google.ai.edge.litertlm.JsonConvertersKt")
            .getMethod("toKotlinValue", JsonElement::class.java)
            .invoke(null, JsonParser.parseString(json)) as Map<String, Any?>
    }
    fun decision(text: String = "", calls: List<ToolCall> = emptyList()): String =
        AgentDecisionMapping.decision(text, calls)
    fun rejects(label: String, text: String = "", calls: List<ToolCall> = emptyList()) {
        expect(label, runCatching { decision(text, calls) }.exceptionOrNull() is IllegalArgumentException)
    }
    val calls = listOf(
        ToolCall("answer", mapOf("text" to "Hello")),
        ToolCall("clarify", mapOf("text" to "Which record?")),
        ToolCall("read", mapOf("entity" to "Note")),
        ToolCall("create", mapOf("entity" to "Satellite", "values" to """{"name":"Orion","altitudeKm":500,"active":true,"optional":null}""")),
        ToolCall("update", mapOf("entity" to "Note", "id" to "id-1", "values" to """{"text":"updated"}""")),
        ToolCall("delete_preview", mapOf("entity" to "Note", "id" to "id-1")),
        ToolCall("calculate", sdkArguments("""{"op":"add","values":[2,3.5]}""")),
    )
    for (call in calls) {
        val encoded = decision(calls = listOf(call))
        val parsed = JsonParser.parseString(encoded).asJsonObject
        expect("Tool name maps to exact action tag", parsed.get("tag").asString == call.name)
        expect("Already-valid action JSON stays semantically identical", JsonParser.parseString(decision(encoded)) == parsed)
        val envelope = StandardMethodCodec.INSTANCE.encodeSuccessEnvelope(encoded)
        envelope.flip()
        expect("Mapped decision crosses actual codec unchanged", StandardMethodCodec.INSTANCE.decodeEnvelope(envelope) == encoded)
    }
    val create = JsonParser.parseString(decision(calls = listOf(calls[3]))).asJsonObject.getAsJsonObject("values")
    expect("Create values are an object, not a JSON string", create.get("altitudeKm").asInt == 500)
    expect("Nullable values are preserved", create.has("optional") && create.get("optional").isJsonNull)
    expect("Boolean values are preserved", create.get("active").asBoolean)
    val calculation = JsonParser.parseString(decision(calls = listOf(calls[6]))).asJsonObject
    expect("Calculation has no top-level op or values", calculation.keySet() == setOf("tag", "calculation"))
    expect("Nested decimal survives exact mapping", calculation.getAsJsonObject("calculation").getAsJsonArray("values")[1].asDouble == 3.5)
    for (operator in listOf("sum", "add", "mean", "min", "max", "multiply", "subtract", "divide")) {
        val encoded = decision(calls = listOf(ToolCall("calculate", mapOf("op" to operator, "values" to listOf(4, 2)))))
        expect("Every supported operator maps without execution", JsonParser.parseString(encoded).asJsonObject.getAsJsonObject("calculation").get("op").asString == operator)
    }
    val longValues = decision(calls = listOf(ToolCall("create", mapOf("entity" to "Note", "values" to """{"value":9223372036854775807}"""))))
    expect("Structured values preserve signed 64-bit precision", JsonParser.parseString(longValues).asJsonObject.getAsJsonObject("values").get("value").asLong == Long.MAX_VALUE)
    val readById = JsonParser.parseString(decision(calls = listOf(ToolCall("read", mapOf("entity" to "Satellite", "id" to "id-2"))))).asJsonObject
    expect("Read identifier remains optional but supported", readById.get("id").asString == "id-2")
    expect("Plain prose becomes answer", JsonParser.parseString(decision("A clear local answer.")).asJsonObject.get("tag").asString == "answer")

    rejects("Empty model output rejects")
    rejects("Whitespace-only output rejects", "   ")
    rejects("Unknown tool rejects", calls = listOf(ToolCall("sql", emptyMap())))
    rejects("Multiple tools reject", calls = calls.take(2))
    rejects("Mixed decision text and tool reject", "Hello", listOf(calls[0]))
    rejects("Extra tool field rejects", calls = listOf(ToolCall("read", mapOf("entity" to "Note", "url" to "invalid"))))
    rejects("Missing tool field rejects", calls = listOf(ToolCall("update", mapOf("entity" to "Note", "values" to "{}"))))
    rejects("Null identifier rejects", calls = listOf(ToolCall("read", mapOf("entity" to "Note", "id" to null))))
    rejects("Non-string entity rejects", calls = listOf(ToolCall("read", mapOf("entity" to 2))))
    rejects("Object values parameter must be JSON string", calls = listOf(ToolCall("create", mapOf("entity" to "Note", "values" to emptyMap<String, Any>()))))
    rejects("Values JSON must be an object", calls = listOf(ToolCall("create", mapOf("entity" to "Note", "values" to "[]"))))
    rejects("Malformed values JSON rejects", calls = listOf(ToolCall("create", mapOf("entity" to "Note", "values" to "{bad}"))))
    rejects("Duplicate values keys reject", calls = listOf(ToolCall("create", mapOf("entity" to "Note", "values" to """{"text":"a","text":"b"}"""))))
    rejects("Null calculation value rejects", calls = listOf(ToolCall("calculate", mapOf("op" to "add", "values" to listOf(1, null)))))
    rejects("Boolean calculation value rejects", calls = listOf(ToolCall("calculate", mapOf("op" to "add", "values" to listOf(true)))))
    rejects("Unknown calculation rejects", calls = listOf(ToolCall("calculate", mapOf("op" to "eval", "values" to listOf(1)))))
    rejects("Nonfinite calculation rejects", calls = listOf(ToolCall("calculate", mapOf("op" to "add", "values" to listOf(Double.NaN)))))
    rejects("Division by zero rejects", calls = listOf(ToolCall("calculate", mapOf("op" to "divide", "values" to listOf(1, 0)))))
    rejects("Subtract needs two inputs", calls = listOf(ToolCall("calculate", mapOf("op" to "subtract", "values" to listOf(1)))))
    rejects("Calculation input count is bounded", calls = listOf(ToolCall("calculate", mapOf("op" to "add", "values" to List(1001) { 1 }))))
    rejects("Empty calculation rejects", calls = listOf(ToolCall("calculate", mapOf("op" to "add", "values" to emptyList<Int>()))))
    rejects("Malformed action JSON never becomes prose", "{not json}")
    rejects("Unknown action JSON rejects", """{"tag":"sql","text":"invalid"}""")
    rejects("Extra action field rejects", """{"tag":"answer","text":"ok","extra":1}""")
    rejects("Action array rejects", """[{"tag":"answer","text":"ok"}]""")
    rejects("Fenced JSON rejects", "```json\n{\"tag\":\"answer\",\"text\":\"ok\"}\n```")
    rejects("JSON embedded in prose rejects", "Result: {\"tag\":\"answer\",\"text\":\"ok\"}")
    rejects("Duplicate action keys reject", """{"tag":"answer","tag":"read","text":"ok"}""")
    rejects("Extra trailing JSON rejects", """{"tag":"answer","text":"ok"} {}""")
    rejects("JSON primitive is not prose", "null")
    rejects("Oversized text rejects", "a".repeat(32769))
    fun rejectsInput(label: String, input: Any?) {
        expect(label, runCatching { LiteRtAgentBridge.generationInput(input) }.exceptionOrNull() is IllegalArgumentException)
    }
    expect("Generation input preserves the complete prompt", LiteRtAgentBridge.generationInput(mapOf("prompt" to "full envelope", "maxTokens" to 512)).prompt == "full envelope")
    expect("Maximum prompt boundary is accepted", LiteRtAgentBridge.generationInput(mapOf("prompt" to "x".repeat(32768), "maxTokens" to 512)).prompt.length == 32768)
    expect("Minimum token budget is accepted", LiteRtAgentBridge.generationInput(mapOf("prompt" to "x", "maxTokens" to 1)).maxTokens == 1)
    rejectsInput("Extra generation field rejects", mapOf("prompt" to "x", "maxTokens" to 1, "path" to "unused"))
    rejectsInput("Missing token budget rejects", mapOf("prompt" to "x"))
    rejectsInput("Zero token budget rejects", mapOf("prompt" to "x", "maxTokens" to 0))
    rejectsInput("Oversized token budget rejects", mapOf("prompt" to "x", "maxTokens" to 513))
    rejectsInput("Noninteger token budget rejects", mapOf("prompt" to "x", "maxTokens" to 2.0))
    rejectsInput("Empty prompt rejects", mapOf("prompt" to " ", "maxTokens" to 1))
    rejectsInput("Oversized prompt rejects", mapOf("prompt" to "x".repeat(32769), "maxTokens" to 1))
    expect("Current uncancelled completion is accepted", LiteRtAgentBridge.acceptsCompletion(1, 1, false, false, 9, 10))
    expect("Cancelled completion is fenced", !LiteRtAgentBridge.acceptsCompletion(1, 1, true, false, 9, 10))
    expect("Superseded epoch is fenced", !LiteRtAgentBridge.acceptsCompletion(1, 2, false, false, 9, 10))
    expect("Disposed completion is fenced", !LiteRtAgentBridge.acceptsCompletion(1, 1, false, true, 9, 10))
    expect("Deadline equality is expired", !LiteRtAgentBridge.acceptsCompletion(1, 1, false, false, 10, 10))
    val tools = AgentDecisionMapping.tools()
    expect("Exactly seven manual tools are registered", tools.size == 7)
    for (tool in tools) {
        expect("Every automatic execution body throws", runCatching { tool.execute("{}") }.exceptionOrNull() is UnsupportedOperationException)
        expect("Every tool declares strict additional fields", JsonParser.parseString(tool.getToolDescriptionJsonString()).asJsonObject.getAsJsonObject("parameters").get("additionalProperties").asBoolean == false)
    }
    val nativeOwner = Any()
    var closeAttempts = 0
    var closeThrows = true
    val owner = RetainedEngineOwner<Any> {
        check(it === nativeOwner)
        closeAttempts++
        if (closeThrows) error("Synthetic close failure")
    }
    owner.retain(nativeOwner)
    expect("Throwing close surfaces failure", runCatching { owner.close() }.isFailure)
    expect("Throwing close retains exact ownership", owner.value === nativeOwner)
    expect("Unreleased ownership rejects replacement", runCatching { owner.retain(Any()) }.isFailure)
    closeThrows = false
    owner.close()
    expect("Successful retry closes the same owner", closeAttempts == 2)
    expect("Only successful close clears ownership", owner.value == null)
    owner.close()
    expect("Already released owner does not close twice", closeAttempts == 2)
    println("AGENT_DECISION_MAPPING_CHECKS=$checks PASS")
}
