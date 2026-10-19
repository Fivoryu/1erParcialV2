package com.diagrammer.diagrammer_mobile

import com.google.gson.internal.LazilyParsedNumber
import io.flutter.plugin.common.StandardMethodCodec
import java.math.BigDecimal
import java.math.BigInteger

/** Converts SDK JSON values without leaking SDK-specific objects across the channel. */
internal object TrialChannelValues {
    fun normalize(value: Any?): Any? {
        var nodes = 0
        var characters = 0
        fun boundedString(text: String): String {
            require(text.length <= 32768) { "Trial string exceeds limits." }
            characters += text.length
            require(characters <= 65536) { "Trial JSON exceeds limits." }
            return text
        }
        fun visit(item: Any?, depth: Int): Any? {
            require(depth <= 16 && ++nodes <= 2048) { "Trial JSON structure exceeds limits." }
            return when (item) {
                null -> null
                is Boolean -> item
                is String -> boundedString(item)
                is Map<*, *> -> {
                    require(item.size <= 2048) { "Trial object exceeds limits." }
                    val result = linkedMapOf<String, Any?>()
                    for ((key, child) in item) {
                        require(key is String) { "Trial object keys must be strings." }
                        result[boundedString(key)] = visit(child, depth + 1)
                    }
                    result
                }
                is List<*> -> {
                    require(item.size <= 2048) { "Trial array exceeds limits." }
                    item.map { visit(it, depth + 1) }
                }
                is Number -> number(item)
                else -> throw IllegalArgumentException("Unsupported trial JSON value.")
            }
        }
        return visit(value, 0)
    }

    private fun number(value: Number): Number {
        require(value is Byte || value is Short || value is Int || value is Long ||
            value is Float || value is Double || value is BigInteger || value is BigDecimal ||
            value is LazilyParsedNumber) { "Unsupported trial JSON number." }
        val text = value.toString()
        require(text.length <= 128) { "Trial number exceeds limits." }
        val decimal = text.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Trial number must be finite.")
        if (decimal.signum() == 0 && text.startsWith("-")) return -0.0
        if (decimal.stripTrailingZeros().scale() <= 0) {
            val integer = try {
                decimal.longValueExact()
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Trial integer exceeds signed 64-bit range.")
            }
            return if (integer in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) integer.toInt() else integer
        }
        val floating = decimal.toDouble()
        require(floating.isFinite() && (floating != 0.0 || decimal.signum() == 0)) {
            "Trial decimal exceeds finite double range."
        }
        return floating
    }

    /** Encoding here keeps malformed SDK values out of the main-thread reply callback. */
    fun validateEnvelope(value: Any?) {
        val encoded = StandardMethodCodec.INSTANCE.encodeSuccessEnvelope(value)
        require(encoded.position() <= 65536) { "Trial response exceeds limits." }
    }
}
