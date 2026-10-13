package dev.edgecompanion.core.inference

import kotlin.math.ceil

data class LatencySummary(val count: Int, val p95Ms: Long?, val gatePassed: Boolean?)

/** Acceptance percentiles require 20 samples; a smoke result cannot pass a p95 gate. */
fun summarizeLatency(samplesMs: List<Long>, gateMs: Long): LatencySummary {
    require(gateMs >= 0 && samplesMs.all { it >= 0 })
    if (samplesMs.size < 20) return LatencySummary(samplesMs.size, null, null)
    val p95 = samplesMs.sorted()[ceil(samplesMs.size * 0.95).toInt() - 1]
    return LatencySummary(samplesMs.size, p95, p95 <= gateMs)
}
