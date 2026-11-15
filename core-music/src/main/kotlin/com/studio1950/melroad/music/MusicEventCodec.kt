package com.studio1950.melroad.music

import java.security.MessageDigest

/** 재현성 검증용 직렬화·해시. 필드 순서를 바꾸면 기존 해시와 달라지므로 바꾸지 않는다. */
object MusicEventCodec {
    fun encode(event: MusicEvent): String =
        "${event.atMs},${event.durationMs},${event.instrument.name},${event.pitch},${event.velocity},${event.expression}"

    fun hash(events: List<MusicEvent>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (e in events) {
            digest.update(encode(e).toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
