package com.studio1950.melroad.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.studio1950.melroad.ui.theme.ActiveBlue
import com.studio1950.melroad.ui.theme.HyundaiBlue
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** 장면 바탕색. 도로·자동차 바깥은 이 색으로 서서히 사라진다. */
val SceneBackground = Color(0xFFEEF2F6)

/**
 * 장식용 주행 장면: 위에서 비스듬히 내려다본 차선과 단순한 세단 뒷모습.
 * 자동차 주변만 보이고 바깥은 바탕색으로 페이드아웃된다. 길 안내·지도 기능이 아니며 화면 낭독기에서는 숨긴다.
 *
 * @param speedKmh 차선이 흐르는 속도(표시용, 실제 속도의 일부만 반영)
 * @param curve -1(왼쪽)..1(오른쪽) 도로가 휘는 정도
 * @param climb -1(내리막)..1(오르막) 시점 높이를 살짝 바꾼다
 * @param playing 음악 재생 중이면 경로가 은은하게 빛나고 음파 링이 퍼진다
 */
@Composable
fun DriveScene(
    speedKmh: Float,
    curve: Float,
    climb: Float,
    playing: Boolean,
    density: Int,
    modifier: Modifier = Modifier,
) {
    val curveAnim by animateFloatAsState(curve, spring(stiffness = Spring.StiffnessVeryLow), label = "curve")
    val climbAnim by animateFloatAsState(climb, tween(2500), label = "climb")
    val speedAnim by animateFloatAsState(speedKmh, tween(1500), label = "speed")

    val currentSpeed by rememberUpdatedState(speedAnim)
    var phase by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
                    time += dt
                    phase = (phase + currentSpeed / 3.6f * dt * 0.22f) % 2f
                }
                last = now
            }
        }
    }

    Canvas(modifier.clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        drawRect(SceneBackground)
        val view = View(w, h, vanishY = -h * (0.25f + climbAnim * 0.06f), curve = curveAnim)
        drawLanes(view, phase, playing, time)
        // 자동차는 항상 가운데 차선 중심에 놓는다(그 거리에서의 도로 중심을 투영으로 계산)
        val carY = h * 0.70f
        val carD = (h - view.vanishY) / (carY - view.vanishY)
        val carCenter = Offset(view.at(carD).first, carY)
        drawRings(carCenter, w, playing, time, density)
        drawSedan(carCenter, min(w * 0.17f, h * 0.24f), curveAnim, if (playing) time else 0f, playing)
        // 바깥쪽 페이드아웃: 자동차 주변만 남긴다
        val radius = max(w, h) * 0.62f
        drawRect(
            Brush.radialGradient(
                0f to Color.Transparent,
                0.38f to Color.Transparent,
                0.82f to SceneBackground,
                1f to SceneBackground,
                center = Offset(w * 0.5f, h * 0.64f),
                radius = radius,
            ),
        )
    }
}

private class View(val w: Float, val h: Float, val vanishY: Float, val curve: Float) {
    /** 거리 d(1 = 화면 아래)의 화면 y와 도로 중심 x */
    fun at(d: Float): Pair<Float, Float> {
        val y = vanishY + (h - vanishY) / d
        val bend = 1f - 1f / d
        return (w * 0.5f + curve * w * 0.45f * bend * bend) to y
    }
}

/** 3차선 도로: 바깥 실선, 안쪽 점선, 가운데 차선에 경로 리본 */
private fun DrawScope.drawLanes(v: View, phase: Float, playing: Boolean, time: Float) {
    val laneHalf = v.w * 0.17f // 차선 하나의 반폭(화면 아래 기준)
    val far = 14f
    val ds = (0..40).map { i -> far.pow(i / 40f) }

    fun strip(left: Float, right: Float): Path {
        val p = Path()
        ds.forEachIndexed { i, d ->
            val (cx, y) = v.at(d)
            if (i == 0) p.moveTo(cx + left * laneHalf / d, y) else p.lineTo(cx + left * laneHalf / d, y)
        }
        ds.asReversed().forEach { d ->
            val (cx, y) = v.at(d)
            p.lineTo(cx + right * laneHalf / d, y)
        }
        p.close()
        return p
    }

    // 노면(아주 옅은 회색)
    drawPath(strip(-3.2f, 3.2f), Color(0xFFDDE3EA))
    // 경로 리본(가운데 차선)
    val glow = if (playing) 0.30f + 0.08f * sin(time * 2f * PI.toFloat() / 2.4f) else 0.24f
    drawPath(strip(-0.8f, 0.8f), Brush.verticalGradient(listOf(ActiveBlue.copy(alpha = 0.04f), ActiveBlue.copy(alpha = glow)), startY = 0f, endY = v.h))

    // 바깥 실선
    for (side in listOf(-3f, 3f)) {
        val p = Path()
        ds.forEachIndexed { i, d ->
            val (cx, y) = v.at(d)
            val x = cx + side * laneHalf / d
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, Color(0xFFB4BFCC), style = Stroke(width = v.w * 0.005f, cap = StrokeCap.Round))
    }
    // 안쪽 점선(흐름)
    for (side in listOf(-1f, 1f)) {
        var d0 = 1f + ((2f - (phase % 2f)) % 2f) - 2f
        while (d0 < far) {
            val dA = max(d0, 1f)
            val dB = d0 + 0.9f
            if (dB > 1f) {
                val (xa, ya) = v.at(dA)
                val (xb, yb) = v.at(dB)
                drawLine(
                    Color(0xFFB4BFCC),
                    Offset(xa + side * laneHalf / dA, ya),
                    Offset(xb + side * laneHalf / dB, yb),
                    strokeWidth = v.w * 0.005f,
                    cap = StrokeCap.Round,
                )
            }
            d0 += 2f
        }
    }
}

private fun DrawScope.drawRings(center: Offset, w: Float, playing: Boolean, time: Float, density: Int) {
    if (!playing) return
    val period = when (density) {
        0 -> 3.2f
        1 -> 2.6f
        2 -> 2.1f
        else -> 1.8f
    }
    for (i in 0 until 3) {
        val p = ((time / period) + i / 3f) % 1f
        val rw = w * (0.14f + p * 0.30f)
        drawOval(
            ActiveBlue.copy(alpha = (1f - p) * 0.35f),
            topLeft = Offset(center.x - rw, center.y - rw * 0.30f),
            size = Size(rw * 2f, rw * 0.60f),
            style = Stroke(width = w * 0.003f),
        )
    }
}

/**
 * 위에서 비스듬히 본 단순한 세단 뒷모습(일반형, 특정 차종 아님). 후미등·번호판 같은 세부 없이
 * 면 단위 음영만으로 입체감을 낸다: 범퍼 → 트렁크 → 뒷유리·유리 지붕 → 앞유리 → 보닛.
 */
private fun DrawScope.drawSedan(center: Offset, cw: Float, curve: Float, time: Float, playing: Boolean) {
    val ch = cw * 1.55f // 위에서 보므로 앞뒤 길이가 보인다
    val bounce = if (playing) sin(time * 2f * PI.toFloat() * 1.1f) * ch * 0.004f else 0f
    val cx = center.x
    val bottom = center.y + ch * 0.38f + bounce
    fun yAt(f: Float) = bottom - ch * f

    // 부드러운 바닥 그림자(번짐)
    val shadowCenter = Offset(cx, yAt(0.48f))
    drawOval(
        Brush.radialGradient(
            0f to HyundaiBlue.copy(alpha = 0.22f),
            0.6f to HyundaiBlue.copy(alpha = 0.08f),
            1f to Color.Transparent,
            center = shadowCenter,
            radius = ch * 0.62f,
        ),
        topLeft = Offset(cx - cw * 0.75f, yAt(1.08f)),
        size = Size(cw * 1.5f, ch * 1.2f),
    )

    rotate(curve * 4f, pivot = Offset(cx, yAt(0.5f))) {
        // 바퀴(옆으로 살짝)
        val tireW = cw * 0.11f
        val tireH = ch * 0.17f
        for ((fy, inset) in listOf(0.10f to 0.53f, 0.70f to 0.49f)) {
            for (side in listOf(-1f, 1f)) {
                drawRoundRect(
                    Color(0xFF3A4452),
                    topLeft = Offset(cx + side * cw * inset - tireW / 2, yAt(fy + 0.17f)),
                    size = Size(tireW, tireH),
                    cornerRadius = CornerRadius(tireW * 0.35f),
                )
            }
        }

        // 차체 외곽(뒤가 넓고 앞이 살짝 좁은 형태)
        val body = Path().apply {
            moveTo(cx - cw * 0.42f, yAt(0.0f))
            cubicTo(cx - cw * 0.52f, yAt(0.0f), cx - cw * 0.52f, yAt(0.06f), cx - cw * 0.51f, yAt(0.12f))
            lineTo(cx - cw * 0.50f, yAt(0.62f))
            cubicTo(cx - cw * 0.49f, yAt(0.88f), cx - cw * 0.44f, yAt(1.0f), cx - cw * 0.30f, yAt(1.0f))
            lineTo(cx + cw * 0.30f, yAt(1.0f))
            cubicTo(cx + cw * 0.44f, yAt(1.0f), cx + cw * 0.49f, yAt(0.88f), cx + cw * 0.50f, yAt(0.62f))
            lineTo(cx + cw * 0.51f, yAt(0.12f))
            cubicTo(cx + cw * 0.52f, yAt(0.06f), cx + cw * 0.52f, yAt(0.0f), cx + cw * 0.42f, yAt(0.0f))
            close()
        }
        drawPath(body, Brush.verticalGradient(listOf(Color(0xFFFDFEFF), Color(0xFFE6EBF1), Color(0xFFD3DAE3)), startY = yAt(1f), endY = bottom))
        drawPath(
            body,
            Brush.horizontalGradient(
                listOf(Color(0xFF8A97A8).copy(alpha = 0.35f), Color.Transparent, Color.Transparent, Color(0xFF8A97A8).copy(alpha = 0.35f)),
                startX = cx - cw * 0.52f,
                endX = cx + cw * 0.52f,
            ),
        )
        drawPath(body, Color(0xFFB9C3CF), style = Stroke(width = cw * 0.008f))

        // 범퍼 아래쪽 면(살짝 어둡게)
        drawPath(
            Path().apply {
                moveTo(cx - cw * 0.44f, yAt(0.0f))
                lineTo(cx + cw * 0.44f, yAt(0.0f))
                lineTo(cx + cw * 0.47f, yAt(0.06f))
                lineTo(cx - cw * 0.47f, yAt(0.06f))
                close()
            },
            Color(0xFFC3CCD7),
        )

        // 유리 캐노피: 뒷유리 + 유리 지붕 + 앞유리를 하나의 매끈한 면으로
        val canopy = Path().apply {
            moveTo(cx - cw * 0.38f, yAt(0.24f))
            cubicTo(cx - cw * 0.40f, yAt(0.40f), cx - cw * 0.37f, yAt(0.62f), cx - cw * 0.35f, yAt(0.76f))
            cubicTo(cx - cw * 0.30f, yAt(0.82f), cx + cw * 0.30f, yAt(0.82f), cx + cw * 0.35f, yAt(0.76f))
            cubicTo(cx + cw * 0.37f, yAt(0.62f), cx + cw * 0.40f, yAt(0.40f), cx + cw * 0.38f, yAt(0.24f))
            cubicTo(cx + cw * 0.25f, yAt(0.21f), cx - cw * 0.25f, yAt(0.21f), cx - cw * 0.38f, yAt(0.24f))
            close()
        }
        drawPath(canopy, Brush.verticalGradient(listOf(Color(0xFF55657A), Color(0xFF2E3A49), Color(0xFF3C4A5C)), startY = yAt(0.82f), endY = yAt(0.21f)))
        // 유리 반사광(사선)
        drawPath(
            Path().apply {
                moveTo(cx - cw * 0.22f, yAt(0.25f))
                lineTo(cx - cw * 0.04f, yAt(0.70f))
                lineTo(cx + cw * 0.06f, yAt(0.70f))
                lineTo(cx - cw * 0.12f, yAt(0.25f))
                close()
            },
            Color.White.copy(alpha = 0.10f),
        )
        // 지붕과 뒷유리 경계(은은한 선)
        drawLine(
            Color.White.copy(alpha = 0.18f),
            Offset(cx - cw * 0.33f, yAt(0.40f)),
            Offset(cx + cw * 0.33f, yAt(0.40f)),
            strokeWidth = cw * 0.008f,
        )
        // 사이드미러
        for (side in listOf(-1f, 1f)) {
            drawRoundRect(
                Color(0xFFD6DDE6),
                topLeft = Offset(cx + side * cw * 0.50f - (if (side < 0) cw * 0.09f else 0f), yAt(0.75f)),
                size = Size(cw * 0.09f, ch * 0.035f),
                cornerRadius = CornerRadius(cw * 0.02f),
            )
        }
        // 트렁크 끝 하이라이트
        drawLine(
            Color.White,
            Offset(cx - cw * 0.40f, yAt(0.13f)),
            Offset(cx + cw * 0.40f, yAt(0.13f)),
            strokeWidth = cw * 0.01f,
            cap = StrokeCap.Round,
        )
    }
}
