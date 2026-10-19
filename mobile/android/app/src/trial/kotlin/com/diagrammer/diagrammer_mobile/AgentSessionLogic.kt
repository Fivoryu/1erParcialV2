package com.diagrammer.diagrammer_mobile

/** Pure session helpers: no Android, SDK or device types, so they are checkable on a plain JVM. */
internal object AgentSessionLogic {
    const val MAX_INSTRUCTION_CHARS = 24000
    const val MAX_MESSAGE_CHARS = 16000
    const val MAX_SCOPE_CHARS = 256
    const val MIN_TIMEOUT_MS = 1000
    const val MAX_TIMEOUT_MS = 120000
    const val CONTEXT_FRACTION_PERCENT = 75
    const val CHARS_PER_TOKEN = 3

    data class OpenInput(val scope: String, val systemInstruction: String)
    data class SendInput(val scope: String, val message: String, val maxTokens: Int, val timeoutMs: Int)

    fun estimateTokens(chars: Int): Int = (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /** True when sending [pendingChars] more would push estimated use past 75% of [maxNumTokens]. */
    fun wouldExceedContext(usedChars: Int, pendingChars: Int, maxTokens: Int, maxNumTokens: Int): Boolean {
        val projected = estimateTokens(usedChars + pendingChars) + maxTokens
        return projected * 100 > maxNumTokens * CONTEXT_FRACTION_PERCENT
    }

    fun cpuThreads(availableProcessors: Int): Int = (availableProcessors - 2).coerceIn(2, 6)

    fun openInput(arguments: Any?): OpenInput {
        require(arguments is Map<*, *> && arguments.keys == setOf("scope", "systemInstruction"))
        val scope = arguments["scope"]
        val instruction = arguments["systemInstruction"]
        require(scope is String && scope.isNotBlank() && scope.length <= MAX_SCOPE_CHARS)
        require(instruction is String && instruction.isNotBlank() && instruction.length <= MAX_INSTRUCTION_CHARS)
        return OpenInput(scope, instruction)
    }

    fun sendInput(arguments: Any?): SendInput {
        require(arguments is Map<*, *> && arguments.keys == setOf("scope", "message", "maxTokens", "timeoutMs"))
        val scope = arguments["scope"]
        val message = arguments["message"]
        val tokens = arguments["maxTokens"]
        val timeout = arguments["timeoutMs"]
        require(scope is String && scope.isNotBlank() && scope.length <= MAX_SCOPE_CHARS)
        require(message is String && message.isNotBlank() && message.length <= MAX_MESSAGE_CHARS)
        require(tokens is Int && tokens in 1..512)
        require(timeout is Int && timeout in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS)
        return SendInput(scope, message, tokens, timeout)
    }

    /** Remaining wait in ms, never negative; 0 means the deadline has already passed. */
    fun remainingMs(nowMs: Long, deadlineMs: Long): Long = (deadlineMs - nowMs).coerceAtLeast(0L)
}
