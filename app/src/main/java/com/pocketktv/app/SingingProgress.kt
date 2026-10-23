package com.pocketktv.app

/** Playback evidence, not microphone evidence. Seeking and buffering earn no time. */
class SingingProgress {
    companion object { fun interrupted(played:Long,accompaniment:Long)=SingingProgress().also{it.playedMs=played;it.accompanimentMs=accompaniment;it.reliableCoverage=false} }
    private var lastPosition = -1L
    private var lastClock = -1L
    private val intervals = mutableListOf<LongRange>()
    var playedMs = 0L; private set
    var accompanimentMs = 0L; private set
    var reliableCoverage = true; private set
    val coverageMs get() = intervals.sumOf { it.last - it.first }
    fun snapshot()=SingingProgress().also{copy->copy.lastPosition=lastPosition;copy.lastClock=lastClock;copy.playedMs=playedMs;copy.accompanimentMs=accompanimentMs;copy.reliableCoverage=reliableCoverage;copy.intervals.addAll(intervals)}
    fun discontinuity() { lastPosition = -1; lastClock = -1 }
    fun sample(position: Long, clock: Long, playing: Boolean, accompaniment: Boolean) {
        val elapsed = clock - lastClock
        val delta = position - lastPosition
        if (playing && lastClock >= 0 && elapsed in 1..2000 && delta > 0 && delta <= elapsed * 2 + 300) {
            val credit = minOf(delta, elapsed)
            playedMs += credit
            if (accompaniment) accompanimentMs += credit
            if (reliableCoverage) {
                var start = lastPosition; var end = position
                val iterator = intervals.iterator()
                while (iterator.hasNext()) {
                    val r = iterator.next()
                    if (r.first <= end && r.last >= start) { start = minOf(start, r.first); end = maxOf(end, r.last); iterator.remove() }
                }
                if (intervals.size < 256) intervals.add(start..end) else reliableCoverage = false
            }
        }
        lastPosition = position; lastClock = clock
        if (!playing) discontinuity()
    }
    fun completed(naturalEnd: Boolean, duration: Long): Boolean = naturalEnd && reliableCoverage && duration > 0 &&
        coverageMs >= duration * 85 / 100 && playedMs >= minOf(90_000L, duration * 75 / 100)
    fun accompanimentCompleted(naturalEnd: Boolean, duration: Long) = completed(naturalEnd, duration) && accompanimentMs >= duration * 80 / 100
}
