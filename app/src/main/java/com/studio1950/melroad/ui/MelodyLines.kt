package com.studio1950.melroad.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.studio1950.melroad.music.Instrument
import com.studio1950.melroad.music.MusicEvent
import com.studio1950.melroad.ui.theme.ActiveBlue
import com.studio1950.melroad.ui.theme.SkyBlue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

private const val PAST_MS = 9_000L
private const val LOOKBACK_MS = 4_000L
private const val STEP_MS = 50L
private const val KERNEL = 6
private const val SIGMA = 2.4f
private const val HOLD_MS = 900L
private const val LEAD_REST = 69f
private const val BASS_REST = 40f

/** 정해진 시간 격자 위에서의 음 높이·세기. 격자를 절대 시각에 맞춰 두면 화면이 흘러도 곡선이 떨리지 않는다. */
private class Onset(val atMs: Long, val endMs: Long, val pitch: Int, val velocity: Int)

/** 바닥 흐름선의 위상. 매 프레임 템포에 맞춰 조금씩 앞으로 보낸다(그리기 전용, 상태 저장 안 함). */
private class FloorFlow {
    var phase = 0f
    var lastNanos = 0L
}

/**
 * 지금 연주되는 음악을 입체 리본으로 그린 장식 그래픽(글자 없음). 화면 낭독기에서는 숨기고 의미는 위·아래 글자로 전달한다.
 *
 * - 선율: 가장 높은 음의 흐름을 부드럽게 미끄러지는 리본(윗면·앞면·하이라이트)으로 띄우고, 음이 시작될 때마다 살짝 솟았다 가라앉는다.
 * - 바닥: 원근 격자가 곡의 박자 빠르기로 다가오고, 저음은 바닥 위의 가는 선, 박자 음이 울리면 바닥이 은은하게 밝아진다.
 * - 화음: 먼 뒤쪽의 옅은 띠.
 * 오른쪽 빛나는 점이 '지금'이며 지난 약 9초가 왼쪽으로 흘러간다.
 */
@Composable
fun MelodyLines(
    events: (Long, Long) -> List<MusicEvent>,
    position: () -> Long,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frame = it }
        }
    }
    val live by animateFloatAsState(if (playing) 1f else 0f, tween(700), label = "live")
    val flow = remember { FloorFlow() }

    Canvas(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .clearAndSetSemantics { },
    ) {
        val nanos = frame
        val t = nanos / 1e9f
        val w = size.width
        val h = size.height
        val now = position()
        val playX = w * 0.84f
        val pxPerMs = playX / PAST_MS
        fun x(ms: Long): Float = playX + (ms - now) * pxPerMs
        val horizonY = h * 0.66f

        val list = events(now - PAST_MS - LOOKBACK_MS, now)
        val lead = topVoice(list.filter { it.pitch >= 62 && it.instrument in LEAD })
        val bass = topVoice(list.filter { it.instrument == Instrument.BASS })
        val beats = list.filter { it.instrument == Instrument.BASS || (it.instrument == Instrument.PERC && it.pitch == 36) }

        // 바닥 흐름: 한 박에 격자 한 칸씩 다가온다
        val beatMs = estimateBeatMs(list)
        if (flow.lastNanos != 0L) {
            val dtMs = ((nanos - flow.lastNanos) / 1e6f).coerceIn(0f, 100f)
            flow.phase = (flow.phase + dtMs / beatMs * live) % 1f
        }
        flow.lastNanos = nanos
        val beatEnergy = beats.maxOfOrNull { attack(now - it.atMs) * it.velocity / 110f }?.coerceIn(0f, 1f) ?: 0f

        drawFloor(w, h, horizonY, playX, flow.phase, beatEnergy * live)

        // 공통 시간 격자
        val gridStart = ((now - PAST_MS) / STEP_MS - KERNEL) * STEP_MS
        val count = ((now - gridStart) / STEP_MS).toInt() + 1
        val xs = FloatArray(count) { x(gridStart + it * STEP_MS) }

        // 먼 뒤쪽 화음 띠
        val top = h * 0.10f
        val span = h * 0.44f
        for (e in list) {
            if (e.instrument != Instrument.PAD) continue
            val x0 = x(e.atMs)
            val x1 = x(e.atMs + e.durationMs).coerceAtMost(playX)
            if (x1 < 0f || x0 > playX) continue
            val y = top + (1f - ((e.pitch - 48f) / 30f).coerceIn(0f, 1f)) * span * 0.9f
            drawRoundRect(SkyBlue.copy(alpha = 0.035f), Offset(x0, y - 9.dp.toPx() / 2f), Size(x1 - x0, 9.dp.toPx()), CornerRadius(5.dp.toPx()))
            drawRoundRect(SkyBlue.copy(alpha = 0.07f), Offset(x0, y - 1.5.dp.toPx()), Size(x1 - x0, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
        }

        // 저음: 바닥 위의 가는 선(낮은 음일수록 앞쪽)
        if (bass.isNotEmpty()) {
            val pitch = smooth(held(bass, gridStart, count, BASS_REST))
            val floorH = h - horizonY
            val p = Path()
            for (k in 0 until count) {
                val y = horizonY + floorH * (0.28f + (1f - ((pitch[k] - 26f) / 28f).coerceIn(0f, 1f)) * 0.5f)
                if (k == 0) p.moveTo(xs[k], y) else p.lineTo(xs[k], y)
            }
            drawPath(p, SkyBlue.copy(alpha = 0.10f), style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(p, SkyBlue.copy(alpha = 0.40f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // 선율 리본
        val pitch = smooth(held(lead, gridStart, count, LEAD_REST))
        val swell = FloatArray(count)
        var lo = 0
        for (k in 0 until count) {
            val ts = gridStart + k * STEP_MS
            while (lo < lead.size && lead[lo].atMs < ts - 1_600L) lo++
            var sum = 0f
            var i = lo
            while (i < lead.size && lead[i].atMs <= ts) {
                sum += attack(ts - lead[i].atMs) * lead[i].velocity / 110f
                i++
            }
            swell[k] = sum.coerceIn(0f, 1f)
        }
        // 음마다 솟는 모양이 톱니처럼 꺾이지 않도록 세기 곡선도 한 번 더 고르게 편다.
        val swellSmooth = smooth(swell)
        for (k in 0 until count) swell[k] = swellSmooth[k]
        val energyNow = swell[count - 1]
        val ey = FloatArray(count)
        val th = FloatArray(count)
        for (k in 0 until count) {
            val ts = gridStart + k * STEP_MS
            val base = top + (1f - ((pitch[k] - 58f) / 30f).coerceIn(0f, 1f)) * span
            val lift = swell[k] * h * 0.035f
            // 재생 중에는 리본 전체가 곡의 세기만큼 은은하게 물결친다
            val shimmer = sin(((ts % 7_000L) / 700f) * 2f * PI.toFloat() - t * 2f * PI.toFloat() * 0.55f) *
                h * 0.007f * live * (0.4f + energyNow)
            ey[k] = base - lift + shimmer
            th[k] = h * (0.022f + 0.030f * swell[k])
        }

        fun edge(offset: (Int) -> Float): Path {
            val p = Path()
            for (k in 0 until count) if (k == 0) p.moveTo(xs[k], offset(k)) else p.lineTo(xs[k], offset(k))
            return p
        }
        fun face(upper: (Int) -> Float, lower: (Int) -> Float): Path {
            val p = Path()
            for (k in 0 until count) if (k == 0) p.moveTo(xs[k], upper(k)) else p.lineTo(xs[k], upper(k))
            for (k in count - 1 downTo 0) p.lineTo(xs[k], lower(k))
            p.close()
            return p
        }

        // 바닥에 비친 반사
        val reflection = edge { horizonY + (horizonY - ey[it]) * 0.30f }
        drawPath(reflection, ActiveBlue.copy(alpha = 0.06f), style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(reflection, ActiveBlue.copy(alpha = 0.16f), style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        // 리본 앞면(어두운 면)과 윗면(빛 받는 면)
        drawPath(
            face({ ey[it] }, { ey[it] + th[it] }),
            Brush.verticalGradient(listOf(ActiveBlue.copy(alpha = 0.55f), Color(0xFF0B3A6E).copy(alpha = 0.25f)), startY = top, endY = horizonY),
        )
        drawPath(
            face({ ey[it] - th[it] * 0.55f }, { ey[it] }),
            Brush.verticalGradient(listOf(Color(0xFFCFF2FB).copy(alpha = 0.65f), ActiveBlue.copy(alpha = 0.45f)), startY = top, endY = horizonY),
        )
        // 모서리 하이라이트와 빛 번짐
        val front = edge { ey[it] }
        drawPath(front, ActiveBlue.copy(alpha = 0.16f), style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(
            front,
            Brush.horizontalGradient(listOf(SkyBlue.copy(alpha = 0.6f), Color.White), startX = 0f, endX = playX),
            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // 음이 시작된 자리의 작은 빛
        for (o in lead) {
            val k = ((o.atMs - gridStart) / STEP_MS).toInt()
            if (k !in 0 until count) continue
            val age = (now - o.atMs).coerceAtLeast(0L)
            if (age < 600L) {
                drawCircle(ActiveBlue.copy(alpha = 0.45f * (1f - age / 600f)), radius = 11.dp.toPx(), center = Offset(xs[k], ey[k]))
            }
            drawCircle(Color.White.copy(alpha = (1f - age / PAST_MS.toFloat()).coerceIn(0f, 1f) * 0.9f), radius = 2.6.dp.toPx(), center = Offset(xs[k], ey[k]))
        }

        // '지금': 가는 세로 빛과 리본 끝의 빛나는 점(세기에 따라 커진다)
        drawLine(
            Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.18f + 0.22f * live), Color.Transparent), startY = h * 0.04f, endY = horizonY),
            Offset(playX, h * 0.04f),
            Offset(playX, horizonY),
            strokeWidth = 1.5.dp.toPx(),
        )
        val head = Offset(playX, ey[count - 1])
        val headR = (5f + 6f * energyNow * live).dp.toPx()
        drawCircle(ActiveBlue.copy(alpha = 0.22f), radius = headR * 2.8f, center = head)
        drawCircle(ActiveBlue.copy(alpha = 0.45f), radius = headR * 1.6f, center = head)
        drawCircle(Color.White, radius = headR * 0.75f, center = head)
        drawCircle(ActiveBlue.copy(alpha = 0.10f), radius = headR * 1.4f, center = Offset(playX, horizonY + (horizonY - head.y) * 0.30f))

        // 왼쪽(오래된 쪽)은 부드럽게 사라지게
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.30f to Color.Black, 1f to Color.Black, startX = 0f, endX = w),
            blendMode = BlendMode.DstIn,
        )
    }
}

private val LEAD = setOf(Instrument.PIANO, Instrument.EPIANO, Instrument.STRINGS, Instrument.GUITAR)

/** 같은 시각에 시작하는 음 가운데 가장 높은 음만 남긴다. */
private fun topVoice(events: List<MusicEvent>): List<Onset> =
    events.groupBy { it.atMs }
        .map { (at, group) ->
            val top = group.maxBy { it.pitch }
            Onset(at, at + top.durationMs, top.pitch, top.velocity)
        }
        .sortedBy { it.atMs }

/** 격자마다 '마지막으로 시작한 음'의 높이. 음이 끝나고 잠시 뒤에는 쉼 높이로 돌아간다. */
private fun held(onsets: List<Onset>, gridStart: Long, count: Int, rest: Float): FloatArray {
    val out = FloatArray(count)
    var j = -1
    for (k in 0 until count) {
        val ts = gridStart + k * STEP_MS
        while (j + 1 < onsets.size && onsets[j + 1].atMs <= ts) j++
        out[k] = if (j >= 0 && ts < onsets[j].endMs + HOLD_MS) onsets[j].pitch.toFloat() else rest
    }
    return out
}

private val GAUSS: FloatArray = FloatArray(KERNEL * 2 + 1) { exp(-((it - KERNEL).toFloat().pow(2)) / (2f * SIGMA * SIGMA)) }

/** 음과 음 사이를 미끄러지듯 잇는 가우스 평활(끝은 가장자리 값을 이어 쓴다). */
private fun smooth(values: FloatArray): FloatArray {
    val out = FloatArray(values.size)
    for (k in values.indices) {
        var sum = 0f
        var weight = 0f
        for (i in -KERNEL..KERNEL) {
            val v = values[(k + i).coerceIn(0, values.size - 1)]
            val g = GAUSS[i + KERNEL]
            sum += v * g
            weight += g
        }
        out[k] = sum / weight
    }
    return out
}

/** 음이 시작된 뒤의 세기 변화: 짧게 올라갔다가 천천히 가라앉는다. */
private fun attack(dtMs: Long): Float = when {
    dtMs < 0L -> 0f
    dtMs < 140L -> dtMs / 140f
    else -> exp(-(dtMs - 140L) / 420f)
}

/** 모든 음이 8분음표 격자 위에 있으므로, 이웃한 시작 시각 간격의 중앙값으로 한 박 길이를 추정한다(70~86 BPM 범위). */
private fun estimateBeatMs(list: List<MusicEvent>): Float {
    val starts = list.asSequence().map { it.atMs }.distinct().sorted().toList()
    val diffs = starts.zipWithNext { a, b -> b - a }.filter { it in 340L..440L }.sorted()
    val eighth = if (diffs.size >= 4) diffs[diffs.size / 2].toFloat() else 366f
    return eighth * 2f
}

/** 원근 바닥: 소실점으로 모이는 세로선 + 박자에 맞춰 다가오는 가로선 + 박자 음에 밝아지는 빛 */
private fun DrawScope.drawFloor(w: Float, h: Float, horizonY: Float, playX: Float, phase: Float, beat: Float) {
    val floorH = h - horizonY
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF00122B).copy(alpha = 0.35f)), startY = horizonY, endY = h),
        topLeft = Offset(0f, horizonY),
        size = Size(w, floorH),
    )
    val vpX = w * 0.5f
    for (i in -6..6) {
        val bottomX = vpX + i * w * 0.16f
        drawLine(Color.White.copy(alpha = 0.045f), Offset(vpX + i * w * 0.035f, horizonY), Offset(bottomX, h), strokeWidth = 1.dp.toPx())
    }
    val lines = 7
    for (k in 0 until lines) {
        val s = (k + phase) / lines
        val y = horizonY + floorH * s.pow(2.2f)
        drawLine(Color.White.copy(alpha = (0.03f + 0.10f * s) * (1f + beat * 0.8f)), Offset(0f, y), Offset(w, y), strokeWidth = (0.6f + 1.2f * s).dp.toPx())
    }
    drawLine(
        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent)),
        Offset(0f, horizonY),
        Offset(w, horizonY),
        strokeWidth = 1.dp.toPx(),
    )
    if (beat > 0.01f) {
        drawOval(
            Brush.radialGradient(
                listOf(ActiveBlue.copy(alpha = 0.22f * beat), Color.Transparent),
                center = Offset(playX, horizonY + floorH * 0.35f),
                radius = w * 0.22f,
            ),
            topLeft = Offset(playX - w * 0.22f, horizonY - floorH * 0.05f),
            size = Size(w * 0.44f, floorH * 0.8f),
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
