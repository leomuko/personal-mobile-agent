package dev.edgecompanion.app.support

/** Test-only acceptance, evaluated on-device before the next request may allocate. */
data class V4WorkerPoint(val phase: String, val at: Long, val pssKb: Long, val pid: Long)
data class V4MemoryPoint(val stage: String, val elapsed: Long, val eventElapsed: Long, val policy: String,
    val backend: String, val lowMemory: Boolean, val thermal: Int, val available: Long,
    val threshold: Long, val pss: Long, val rss: Long)
data class V4CaseEvidence(
    val decisionValid: Boolean, val deliveryEligible: Boolean, val validationPresent: Boolean,
    val runtimeClean: Boolean, val encoderClean: Boolean, val unloaded: Boolean,
    val telemetryFailed: Boolean, val resourcesSafe: Boolean, val diagnosticPresent: Boolean,
    val decisionMs: Long, val cleanupMs: Long, val start: Long, val end: Long,
    val inputTokens: Int, val outputTokens: Int,
    val worker: String, val deathConfirmed: Boolean, val deathAt: Long, val workerPoints: List<V4WorkerPoint>,
    val loads: List<Long>, val memorySchema: Int, val memoryPolicy: String,
    val memoryStart: Long, val memoryElapsed: Long, val dropped: Int, val failed: Int, val memory: List<V4MemoryPoint>,
)

object V4CaseGate {
    fun accepted(c: V4CaseEvidence, seenWorkers: Set<String>, expectedPolicy: String = "memory-baseline-v1"): Boolean = with(c) {
        val pressure = expectedPolicy == "memory-android-pressure-test-v1"
        if (!pressure && expectedPolicy != "memory-baseline-v1") return false
        if (!decisionValid || !deliveryEligible || !validationPresent || !runtimeClean || !encoderClean || !unloaded ||
            telemetryFailed || !resourcesSafe || !diagnosticPresent ||
            decisionMs !in 0 until 30_000 || start < 0 || end < start || end - start !in decisionMs until 30_000 ||
            cleanupMs !in 0..(end - start) || inputTokens !in 1..1536 || outputTokens !in 1..512 ||
            worker.isBlank() || worker in seenWorkers || !deathConfirmed || deathAt !in start..end ||
            loads.size != 1 || loads.any { it !in deathAt..end } || workerPoints.size < 3 ||
            workerPoints.take(3).map { it.phase } != listOf("CHECKING", "LOADING", "ENCODING")) return false
        if (workerPoints.withIndex().any { (i, p) -> p.at !in start..deathAt || p.pssKb !in 1..(384L * 1024) ||
                p.pid <= 0 || (i > 0 && p.at < workerPoints[i - 1].at) }) return false
        if (memorySchema != 1 || memoryPolicy != expectedPolicy || memoryStart !in start..end ||
            memoryElapsed !in 0..(end - memoryStart) || dropped != 0 || failed != 0 || memory.isEmpty() ||
            !listOf("BASELINE", "LOADING", "PREFILL", "DECODING", "POST_RELEASE").all { stage -> memory.any { it.stage == stage } }) return false
        if (memory.withIndex().any { (i, p) -> p.elapsed !in 0..memoryElapsed || p.eventElapsed !in 0..p.elapsed ||
                p.policy != memoryPolicy || p.backend != "GPU" || p.lowMemory || p.thermal !in 0..2 ||
                p.available < 0 || (!pressure && p.available < (3L shl 30)) || p.threshold <= 0 || p.pss <= 0 || p.rss <= 0 ||
                (i > 0 && (p.elapsed < memory[i - 1].elapsed || p.eventElapsed < memory[i - 1].eventElapsed)) }) return false
        loads == memory.filter { it.stage == "LOADING" }.map { memoryStart + it.eventElapsed }
    }
}
