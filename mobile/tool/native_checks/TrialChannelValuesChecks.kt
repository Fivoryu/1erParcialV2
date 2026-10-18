package com.diagrammer.diagrammer_mobile

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.flutter.plugin.common.StandardMethodCodec

/** Standalone JVM checks against the pinned SDK converter and actual Flutter codec. */
fun main() {
    var checks = 0

    fun expect(label: String, condition: Boolean) {
        check(condition) { label }
        checks++
    }

    // Kotlin marks this SDK utility internal, but its published JVM entry point is public.
    fun sdkValue(json: String): Any? = Class.forName("com.google.ai.edge.litertlm.JsonConvertersKt")
        .getMethod("toKotlinValue", JsonElement::class.java)
        .invoke(null, JsonParser.parseString(json))

    fun rejects(label: String, value: Any?) {
        val error = runCatching { TrialChannelValues.normalize(value) }.exceptionOrNull()
        expect(label, error is IllegalArgumentException)
    }

    fun envelopeRejects(label: String, value: Any?) {
        val error = runCatching { TrialChannelValues.validateEnvelope(value) }.exceptionOrNull()
        expect(label, error is IllegalArgumentException)
    }

    fun encodedRoundTrip(value: Any?): Any? {
        TrialChannelValues.validateEnvelope(value)
        val encoded = StandardMethodCodec.INSTANCE.encodeSuccessEnvelope(value)
        // The encoder returns a filled buffer whose position is at the end.
        encoded.flip()
        return StandardMethodCodec.INSTANCE.decodeEnvelope(encoded)
    }

    fun normalizedRoundTrip(value: Any?): Any? =
        encodedRoundTrip(TrialChannelValues.normalize(value))

    val parsedArguments = sdkValue(
        """{"op":"add","values":[2,3.5],"nested":{"nil":null,"bool":true,"string":"2"}}""",
    )
    // Match the bridge: normalize SDK arguments, not the already-supported metrics.
    val reply = mapOf(
        "text" to "",
        "toolCalls" to listOf(
            mapOf("name" to "calculate", "arguments" to TrialChannelValues.normalize(parsedArguments)),
        ),
        "metrics" to mapOf("inferenceMs" to 10L),
    )
    val decoded = encodedRoundTrip(reply) as Map<*, *>
    expect("Existing metric representation survives the codec", (decoded["metrics"] as Map<*, *>)["inferenceMs"] == 10L)
    val call = (decoded["toolCalls"] as List<*>).single() as Map<*, *>
    val arguments = call["arguments"] as Map<*, *>
    val numbers = arguments["values"] as List<*>
    expect("SDK integer becomes an exact supported Int", numbers[0] is Int && numbers[0] == 2)
    expect("SDK decimal becomes a finite supported Double", numbers[1] is Double && numbers[1] == 3.5)
    val nested = arguments["nested"] as Map<*, *>
    expect("Null remains present and null", nested.containsKey("nil") && nested["nil"] == null)
    expect("Boolean is not converted to a string", nested["bool"] is Boolean && nested["bool"] == true)
    expect("Numeric-looking string stays a string", nested["string"] is String && nested["string"] == "2")

    expect("Long maximum remains exact", normalizedRoundTrip(sdkValue("9223372036854775807")) == Long.MAX_VALUE)
    expect("Long minimum remains exact", normalizedRoundTrip(sdkValue("-9223372036854775808")) == Long.MIN_VALUE)
    expect("Integer above Int range becomes Long", normalizedRoundTrip(sdkValue("2147483648")) == 2147483648L)
    expect("Integral exponent remains exact", normalizedRoundTrip(sdkValue("1e3")) == 1000)
    expect("Decimal exponent remains numeric", normalizedRoundTrip(sdkValue("1e-3")) == 0.001)
    expect(
        "SDK negative zero sign survives encoding",
        java.lang.Double.doubleToRawLongBits(normalizedRoundTrip(sdkValue("-0.0")) as Double) == Long.MIN_VALUE,
    )
    expect(
        "Native negative zero sign survives encoding",
        java.lang.Double.doubleToRawLongBits(normalizedRoundTrip(-0.0) as Double) == Long.MIN_VALUE,
    )
    expect("Null root survives encoding", normalizedRoundTrip(null) == null)
    expect("False root stays boolean", normalizedRoundTrip(false) == false)
    expect("False-looking root stays string", normalizedRoundTrip("false") == "false")

    rejects("Integer above Long maximum is rejected", sdkValue("9223372036854775808"))
    rejects("Integer below Long minimum is rejected", sdkValue("-9223372036854775809"))
    rejects("Decimal overflow is rejected", sdkValue("1e400"))
    rejects("Nonzero decimal underflow is rejected", sdkValue("1e-400"))
    rejects("NaN is rejected", Double.NaN)
    rejects("Positive infinity is rejected", Double.POSITIVE_INFINITY)
    rejects("Negative infinity is rejected", Double.NEGATIVE_INFINITY)
    rejects("Non-string map key is rejected", mapOf(2 to "bad"))
    rejects("Null map key is rejected", mapOf(null to "bad"))
    rejects("Unknown object is rejected", Any())
    val customNumber = object : Number() {
        override fun toByte() = 1.toByte()
        override fun toShort() = 1.toShort()
        override fun toInt() = 1
        override fun toLong() = 1L
        override fun toFloat() = 1f
        override fun toDouble() = 1.0
        override fun toString() = "1"
    }
    rejects("Custom Number implementation is rejected", customNumber)

    fun nestedList(depth: Int): Any? {
        var value: Any? = 0
        repeat(depth) { value = listOf(value) }
        return value
    }
    expect("Depth sixteen is accepted with root depth zero", TrialChannelValues.normalize(nestedList(16)) != null)
    rejects("Depth seventeen is rejected", nestedList(17))
    expect("Exactly 2048 nodes are accepted", (TrialChannelValues.normalize(List(2047) { 0 }) as List<*>).size == 2047)
    rejects("Node 2049 is rejected", List(2048) { 0 })
    expect("String boundary is accepted", TrialChannelValues.normalize("a".repeat(32768)) == "a".repeat(32768))
    rejects("Oversized string is rejected", "a".repeat(32769))
    rejects("Oversized string key is rejected", mapOf("k".repeat(32769) to 1))
    rejects("Aggregate character budget is enforced", List(3) { "a".repeat(21846) })
    envelopeRejects("Envelope framing counts toward byte limit", listOf("a".repeat(32768), "b".repeat(32768)))
    envelopeRejects("Multibyte UTF-8 encoded size is bounded", "€".repeat(32768))
    envelopeRejects("Actual codec rejects unsupported objects", Any())

    val greeting = mapOf(
        "text" to "Hello",
        "toolCalls" to emptyList<Any>(),
        "metrics" to mapOf("inferenceMs" to 10L, "peakPssKb" to 100000L),
    )
    expect("No-call greeting and metrics round-trip unchanged", encodedRoundTrip(greeting) == greeting)
    expect("Envelope validation does not mutate metrics", (greeting["metrics"] as Map<*, *>)["inferenceMs"] == 10L)
    println("CHANNEL_NORMALIZER_CODEC_CHECKS=$checks PASS")
}
