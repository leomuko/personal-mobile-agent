package dev.edgecompanion.app.support

/** Allocation checks are independent of whether a request intentionally cancels or rejects. */
data class ConstraintAllocationEvidence(val start: Long, val end: Long, val worker: String,
    val deathConfirmed: Boolean, val deathAt: Long, val workerPoints: List<V4WorkerPoint>, val loads: List<Long>,
    val memoryStart: Long, val memoryElapsed: Long, val memory: List<V4MemoryPoint>,
    val memorySchema: Int, val memoryPolicy: String, val dropped: Int, val failed: Int,
    val firstTextObserved: Boolean)

object ConstraintAllocationGate {
    const val POLICY = "memory-android-pressure-test-v1"
    fun accepted(c: ConstraintAllocationEvidence, seenWorkers: Set<String>): Boolean = with(c) {
        if (start < 0 || end - start !in 0 until 30_000 || worker.isBlank() || worker in seenWorkers ||
            !deathConfirmed || deathAt !in start..end || loads.size != 1 || loads.single() !in deathAt..end ||
            workerPoints.size < 3 || workerPoints.take(3).map { it.phase } != listOf("CHECKING", "LOADING", "ENCODING")) return false
        if (workerPoints.withIndex().any { (i, p) -> p.at !in start..deathAt || p.pid <= 0 ||
            p.pssKb !in 1..(384L * 1024) || (i > 0 && p.at < workerPoints[i - 1].at) }) return false
        if (memorySchema != 1 || memoryPolicy != POLICY || memoryStart !in start..end ||
            memoryElapsed !in 0..(end - memoryStart) || dropped != 0 || failed != 0 || memory.isEmpty()) return false
        val required = listOf("BASELINE", "LOADING", "PREFILL", "POST_RELEASE") + if (firstTextObserved) listOf("DECODING") else emptyList()
        if (required.any { stage -> memory.none { it.stage == stage } }) return false
        if (memory.withIndex().any { (i, p) -> p.elapsed !in 0..memoryElapsed || p.eventElapsed !in 0..p.elapsed ||
            p.policy != POLICY || p.backend != "GPU" || p.lowMemory || p.thermal !in 0..2 || p.available < 0 ||
            p.threshold <= 0 || p.pss <= 0 || p.rss <= 0 || (i > 0 &&
                (p.elapsed < memory[i - 1].elapsed || p.eventElapsed < memory[i - 1].eventElapsed)) }) return false
        loads == memory.filter { it.stage == "LOADING" }.map { memoryStart + it.eventElapsed }
    }
}
