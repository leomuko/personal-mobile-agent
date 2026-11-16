package dev.edgecompanion.core.sources.data

import dev.edgecompanion.core.sources.model.*
internal data class PassageCandidate(val id: Long, val score: Double)
/** Min-heap whose root is the worst candidate. No passage text or match blobs retained. */
internal class PassageCandidates {
    private val heap = mutableListOf<PassageCandidate>()
    val size: Int get() = heap.size
    private fun worse(a: PassageCandidate, b: PassageCandidate) =
        a.score < b.score || (a.score == b.score && a.id > b.id)
    fun add(candidate: PassageCandidate) {
        if (heap.size == PassageLimits.CANDIDATES) {
            if (!worse(heap[0], candidate)) return
            heap[0] = candidate
            var parent = 0
            while (true) {
                val left = 2 * parent + 1
                if (left >= heap.size) break
                val right = left + 1
                val child = if (right < heap.size && worse(heap[right], heap[left])) right else left
                if (!worse(heap[child], heap[parent])) break
                val value = heap[parent]; heap[parent] = heap[child]; heap[child] = value
                parent = child
            }
        } else {
            heap += candidate
            var child = heap.lastIndex
            while (child > 0) {
                val parent = (child - 1) / 2
                if (!worse(heap[child], heap[parent])) break
                val value = heap[parent]; heap[parent] = heap[child]; heap[child] = value
                child = parent
            }
        }
    }
    fun best(): List<PassageCandidate> = heap.sortedWith(compareByDescending<PassageCandidate> { it.score }.thenBy { it.id })
}
