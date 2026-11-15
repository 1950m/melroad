package com.studio1950.melroad.audio

import com.studio1950.melroad.music.MusicEvent

/**
 * 시각순으로 정렬된 MusicEvent 목록과 재생 커서.
 * 작곡 스레드(메인)에서 추가하고 오디오 스레드에서 꺼내므로 모든 접근을 lock으로 감싼다.
 */
class EventTimeline {
    private val lock = Any()
    private val events = ArrayList<MusicEvent>()
    private var cursor = 0

    fun reset(newEvents: List<MusicEvent>, positionMs: Long) = synchronized(lock) {
        events.clear()
        events.addAll(newEvents.sortedBy { it.atMs })
        cursor = lowerBound(positionMs)
    }

    fun append(more: List<MusicEvent>) = synchronized(lock) {
        for (e in more.sortedBy { it.atMs }) {
            // 이미 지나간 시각의 이벤트는 커서 바로 다음에 넣어 곧바로 연주되게 한다.
            val index = maxOf(upperBound(e.atMs), cursor)
            events.add(index, e)
        }
    }

    /** untilMs 미만에 시작하는 이벤트를 out에 옮긴다. */
    fun drain(untilMs: Long, out: MutableList<MusicEvent>) = synchronized(lock) {
        while (cursor < events.size && events[cursor].atMs < untilMs) {
            out.add(events[cursor])
            cursor++
        }
    }

    private fun lowerBound(ms: Long): Int {
        var lo = 0
        var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].atMs < ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun upperBound(ms: Long): Int {
        var lo = 0
        var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].atMs <= ms) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
