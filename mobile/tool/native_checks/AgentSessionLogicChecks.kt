package com.diagrammer.diagrammer_mobile

/** Standalone checks for the pure session helpers; stdlib only, no engine or device. */
fun main() {
    var checks = 0
    fun expect(label: String, condition: Boolean) {
        check(condition) { label }
        checks++
    }
    fun rejects(label: String, block: () -> Unit) =
        expect(label, runCatching(block).exceptionOrNull() is IllegalArgumentException)
    val s = AgentSessionLogic

    expect("tokens round up", s.estimateTokens(0) == 0 && s.estimateTokens(1) == 1 && s.estimateTokens(3) == 1 && s.estimateTokens(4) == 2)
    // maxNumTokens 4096 -> 75% = 3072 tokens.
    expect("small use fits", !s.wouldExceedContext(3000, 600, 256, 4096))
    expect("exact boundary fits", !s.wouldExceedContext(3 * (3072 - 256), 0, 256, 4096))
    expect("one token over the boundary exceeds", s.wouldExceedContext(3 * (3072 - 256) + 1, 0, 256, 4096))
    expect("pending chars count", s.wouldExceedContext(6000, 3000, 256, 4096))
    expect("reply budget counts", s.wouldExceedContext(0, 0, 3073, 4096))

    expect("cpu threads clamp low", s.cpuThreads(1) == 2 && s.cpuThreads(2) == 2 && s.cpuThreads(4) == 2)
    expect("cpu threads scale", s.cpuThreads(6) == 4 && s.cpuThreads(8) == 6)
    expect("cpu threads clamp high", s.cpuThreads(16) == 6)

    val open = s.openInput(mapOf("scope" to "b1", "systemInstruction" to "x".repeat(24000)))
    expect("open accepts max instruction", open.scope == "b1" && open.systemInstruction.length == 24000)
    rejects("open rejects oversized instruction") { s.openInput(mapOf("scope" to "b", "systemInstruction" to "x".repeat(24001))) }
    rejects("open rejects blank scope") { s.openInput(mapOf("scope" to " ", "systemInstruction" to "x")) }
    rejects("open rejects extra key") { s.openInput(mapOf("scope" to "b", "systemInstruction" to "x", "y" to 1)) }
    rejects("open rejects null") { s.openInput(null) }

    fun send(message: Any? = "hi", tokens: Any? = 256, timeout: Any? = 30000, scope: Any? = "b") =
        s.sendInput(mapOf("scope" to scope, "message" to message, "maxTokens" to tokens, "timeoutMs" to timeout))
    expect("send accepts bounds", send(tokens = 1, timeout = 1000).maxTokens == 1 && send(tokens = 512, timeout = 120000).timeoutMs == 120000)
    rejects("send rejects tokens 0") { send(tokens = 0) }
    rejects("send rejects tokens 513") { send(tokens = 513) }
    rejects("send rejects timeout 999") { send(timeout = 999) }
    rejects("send rejects timeout 120001") { send(timeout = 120001) }
    rejects("send rejects long message") { send(message = "x".repeat(16001)) }
    rejects("send rejects blank message") { send(message = "  ") }
    rejects("send rejects non-int tokens") { send(tokens = "256") }
    rejects("send rejects missing key") { s.sendInput(mapOf("scope" to "b", "message" to "m")) }

    expect("remaining is clamped", s.remainingMs(10, 25) == 15L && s.remainingMs(30, 25) == 0L)
    println("AgentSessionLogicChecks: $checks checks passed")
}
