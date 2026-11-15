package com.studio1950.melroad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.studio1950.melroad.music.Instrument
import com.studio1950.melroad.music.MusicEvent
import com.studio1950.melroad.ui.theme.ActiveBlue
import com.studio1950.melroad.ui.theme.SkyBlue
import kotlin.math.PI
import kotlin.math.sin

/**
 * 지금 연주되는 음악을 선으로 그린 장식 그래픽(글자 없음). 화면 낭독기에서는 숨기고, 의미는 위·아래 글자로 전달한다.
 *
 * - 선율: 높은 음들의 윤곽을 빛나는 곡선으로 잇는다.
 * - 화음: 지속음을 반투명 리본으로 깐다.
 * - 베이스: 아래쪽 가는 선.
 * - 리듬: 바닥의 작은 맥박.
 * 오른쪽 세로선이 '지금'이며, 지난 약 9초가 왼쪽으로 흘러간다.
 */
@Composable
fun MelodyLines(
    events: (Long, Long) -> List<MusicEvent>,
    position: () -> Long,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableLongStateOf(0L) }
    val isPlaying by rememberUpdatedState(playing)
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frame = it }
        }
    }

    Canvas(modifier.clearAndSetSemantics { }) {
        val tick = frame // 매 프레임 다시 그리기
        val w = size.width
        val h = size.height
        val now = position()
        val past = 9_000L
        val playX = w * 0.82f
        fun x(ms: Long): Float = playX + (ms - now) * (playX / past)
        fun y(pitch: Int): Float = h * 0.86f - ((pitch - 34).coerceIn(0, 62) / 62f) * h * 0.74f

        // 옅은 가로 안내선
        for (i in 1..5) {
            val gy = h * i / 6f
            drawLine(Color.White.copy(alpha = 0.05f), Offset(0f, gy), Offset(w, gy), strokeWidth = 1.5f)
        }
        // 정지·대기 중에도 숨 쉬는 듯한 은은한 물결
        val t = tick / 1e9f
        val breathe = Path()
        var px = 0f
        while (px <= w) {
            val py = h * 0.55f + sin(px / w * 4f * PI.toFloat() + t * 0.8f) * h * 0.04f
            if (px == 0f) breathe.moveTo(px, py) else breathe.lineTo(px, py)
            px += w / 80f
        }
        drawPath(breathe, SkyBlue.copy(alpha = 0.10f), style = Stroke(width = 3f))

        val list = events(now - past - 6_000L, now)
        if (list.isNotEmpty()) {
            clipRect(right = playX) {
                // 화음 리본
                for (e in list) {
                    if (e.instrument != Instrument.PAD && !(e.instrument == Instrument.STRINGS && e.pitch < 70)) continue
                    val x0 = x(e.atMs)
                    val x1 = x(e.atMs + e.durationMs)
                    if (x1 < 0f || x0 > playX) continue
                    drawRoundRect(
                        SkyBlue.copy(alpha = 0.10f + e.velocity / 127f * 0.12f),
                        topLeft = Offset(x0, y(e.pitch) - 5f),
                        size = Size((x1 - x0).coerceAtLeast(8f), 10f),
                        cornerRadius = CornerRadius(5f),
                    )
                }
                // 베이스 선
                val bass = list.filter { it.instrument == Instrument.BASS }
                if (bass.size >= 2) {
                    val p = Path()
                    bass.forEachIndexed { i, e ->
                        val bx = x(e.atMs)
                        val by = y(e.pitch)
                        if (i == 0) p.moveTo(bx, by) else p.lineTo(bx, by)
                        p.lineTo(x(e.atMs + e.durationMs), by)
                    }
                    drawPath(p, SkyBlue.copy(alpha = 0.45f), style = Stroke(width = 3f, cap = StrokeCap.Round))
                }
                // 선율 윤곽: 같은 시각에 시작하는 음 가운데 가장 높은 음
                val lead = list
                    .filter { it.pitch >= 62 && it.instrument != Instrument.PERC && it.instrument != Instrument.PAD && it.instrument != Instrument.BASS }
                    .groupBy { it.atMs }
                    .map { (at, group) -> at to group.maxOf { it.pitch } }
                    .sortedBy { it.first }
                if (lead.size >= 2) {
                    val path = Path()
                    lead.forEachIndexed { i, (at, pitch) ->
                        val lx = x(at)
                        val ly = y(pitch)
                        if (i == 0) {
                            path.moveTo(lx, ly)
                        } else {
                            val (pAt, pPitch) = lead[i - 1]
                            val ppx = x(pAt)
                            val ppy = y(pPitch)
                            val mid = (ppx + lx) / 2f
                            path.cubicTo(mid, ppy, mid, ly, lx, ly)
                        }
                    }
                    val fade = Brush.horizontalGradient(
                        listOf(Color.Transparent, ActiveBlue.copy(alpha = 0.6f), Color.White),
                        startX = 0f,
                        endX = playX,
                    )
                    drawPath(path, ActiveBlue.copy(alpha = 0.08f), style = Stroke(width = 26f, cap = StrokeCap.Round))
                    drawPath(path, ActiveBlue.copy(alpha = 0.20f), style = Stroke(width = 12f, cap = StrokeCap.Round))
                    drawPath(path, fade, style = Stroke(width = 4.5f, cap = StrokeCap.Round))
                }
                // 음이 시작된 점
                for ((at, pitch) in lead) {
                    val lx = x(at)
                    if (lx < 0f) continue
                    val age = (now - at).coerceAtLeast(0L)
                    val alpha = (1f - age / past.toFloat()).coerceIn(0f, 1f)
                    if (age < 700L) {
                        drawCircle(ActiveBlue.copy(alpha = 0.35f * (1f - age / 700f)), radius = 22f, center = Offset(lx, y(pitch)))
                    }
                    drawCircle(Color.White.copy(alpha = alpha), radius = 5.5f, center = Offset(lx, y(pitch)))
                }
                // 리듬 맥박(바닥)
                for (e in list) {
                    if (e.instrument != Instrument.PERC) continue
                    val bx = x(e.atMs)
                    if (bx < 0f) continue
                    val age = (now - e.atMs).coerceAtLeast(0L)
                    val alpha = (1f - age / past.toFloat()).coerceIn(0f, 1f) * 0.6f
                    val barH = h * (if (e.pitch == 36) 0.07f else 0.035f) * (e.velocity / 50f)
                    drawLine(
                        Color.White.copy(alpha = alpha),
                        Offset(bx, h * 0.97f),
                        Offset(bx, h * 0.97f - barH),
                        strokeWidth = if (e.pitch == 36) 5f else 3f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        // '지금' 세로선
        drawLine(
            Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = if (isPlaying) 0.55f else 0.3f), Color.Transparent)),
            Offset(playX, h * 0.06f),
            Offset(playX, h * 0.94f),
            strokeWidth = 2.5f,
        )
    }
}

/** 첫 화면 장식: 천천히 흐르는 세 줄의 선율 물결 */
@Composable
fun IdleWaves(modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frame = it }
        }
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val t = frame / 1e9f
        val w = size.width
        val h = size.height
        val colors = listOf(ActiveBlue.copy(alpha = 0.35f), SkyBlue.copy(alpha = 0.22f), Color.White.copy(alpha = 0.16f))
        for ((i, c) in colors.withIndex()) {
            val p = Path()
            var px = 0f
            while (px <= w) {
                val phase = px / w * (2.2f + i * 0.7f) * 2f * PI.toFloat() + t * (0.35f + i * 0.12f)
                val py = h * (0.62f + i * 0.08f) + sin(phase) * h * (0.10f - i * 0.02f)
                if (px == 0f) p.moveTo(px, py) else p.lineTo(px, py)
                px += w / 90f
            }
            drawPath(p, c, style = Stroke(width = 4f - i, cap = StrokeCap.Round))
        }
    }
}
