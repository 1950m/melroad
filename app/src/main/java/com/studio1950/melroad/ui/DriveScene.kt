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
 * @param playing 음악 재생 중이면 경로 리본 위로 빛이 앞으로 흘러간다
 */
@Composable
fun DriveScene(
    speedKmh: Float,
    curve: Float,
    climb: Float,
    playing: Boolean,
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
        drawRoad(view, phase, playing, time)
        // 자동차는 항상 가운데 차선 중심에 놓는다(그 거리에서의 도로 중심을 투영으로 계산)
        val carY = h * 0.70f
        val carD = (h - view.vanishY) / (carY - view.vanishY)
        val carCenter = Offset(view.at(carD).first, carY)
        // 모형처럼 작게: 도로 폭 대비 차 폭이 과하지 않도록 한다.
        val carWidth = min(w * 0.095f, h * 0.13f)
        drawSedan(carCenter, carWidth, curveAnim, if (playing) time else 0f, playing)
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

/**
 * 3차선 도로. 모든 면과 선을 원근(거리 d에 반비례하는 폭)으로 그려 평면 그림처럼 보이지 않게 한다.
 * 갓길 → 연석 → 노면(바퀴 자국) → 가운데 차선 경로 리본(테두리·흐르는 빛) → 차선 순서로 쌓는다.
 */
private fun DrawScope.drawRoad(v: View, phase: Float, playing: Boolean, time: Float) {
    val lane = v.w * 0.17f // 차선 하나의 반폭(화면 아래 기준). 차선 경계는 ±1, ±3
    val far = 14f

    /** 가로 위치 left..right(차선 반폭 단위)를 거리 dFrom..dTo 구간에서 채우는 원근 면 */
    fun band(left: Float, right: Float, dFrom: Float = 1f, dTo: Float = far, steps: Int = 40): Path {
        val p = Path()
        for (i in 0..steps) {
            val d = dFrom * (dTo / dFrom).pow(i / steps.toFloat())
            val (cx, y) = v.at(d)
            val x = cx + left * lane / d
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        for (i in steps downTo 0) {
            val d = dFrom * (dTo / dFrom).pow(i / steps.toFloat())
            val (cx, y) = v.at(d)
            p.lineTo(cx + right * lane / d, y)
        }
        p.close()
        return p
    }
    fun nearToFar(near: Color, farColor: Color) = Brush.verticalGradient(listOf(farColor, near), startY = 0f, endY = v.h)

    // 갓길과 연석(노면보다 살짝 밝은 갓길, 그 안쪽의 얇은 그늘)
    for (side in listOf(-1f, 1f)) {
        drawPath(band(side * 3.2f, side * 4.1f), nearToFar(Color(0xFFE2E7ED), Color(0xFFEAEEF2)))
        drawPath(band(side * 3.2f, side * 3.34f), Color(0xFFC5CED9))
    }
    // 노면: 가까울수록 조금 짙고 멀수록 안개처럼 옅어진다
    drawPath(band(-3.2f, 3.2f), nearToFar(Color(0xFFD2D9E2), Color(0xFFE5EAEF)))
    // 옆 차선의 옅은 바퀴 자국
    for (center in listOf(-2f, 2f)) {
        for (offset in listOf(-0.45f, 0.45f)) {
            drawPath(band(center + offset - 0.10f, center + offset + 0.10f), Color(0xFFC9D1DB).copy(alpha = 0.45f))
        }
    }

    // 경로 리본(가운데 차선): 차 앞에서 가장 진하고 멀어질수록 사라진다
    val glow = if (playing) 0.34f + 0.06f * sin(time * 2f * PI.toFloat() / 2.4f) else 0.26f
    drawPath(band(-0.9f, 0.9f), Brush.verticalGradient(listOf(ActiveBlue.copy(alpha = 0.02f), ActiveBlue.copy(alpha = glow)), startY = 0f, endY = v.h))
    for (side in listOf(-1f, 1f)) {
        drawPath(
            band(side * 0.84f, side * 0.92f),
            Brush.verticalGradient(listOf(ActiveBlue.copy(alpha = 0f), ActiveBlue.copy(alpha = 0.65f)), startY = 0f, endY = v.h),
        )
    }
    // 재생 중: 리본 위로 은은한 빛 띠가 앞으로 흘러간다
    if (playing) {
        for (k in 0 until 3) {
            val u = ((time * 0.16f + k / 3f) % 1f)
            val d0 = far.pow(u * 0.8f)
            val d1 = d0 * 1.35f
            // 띠의 앞뒤 끝이 부드럽게 사라지도록 세로 그라데이션으로 채운다.
            val yNear = v.at(d0).second
            val yFar = v.at(d1).second
            val a = 0.30f * (1f - u) * (1f - u)
            drawPath(
                band(-0.84f, 0.84f, d0, d1, steps = 10),
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = a), Color.White.copy(alpha = 0f)), startY = yFar, endY = yNear),
            )
        }
    }

    // 바깥 실선(흰색, 원근 폭)
    for (side in listOf(-3f, 3f)) {
        drawPath(band(side - 0.05f, side + 0.05f), Color.White)
    }
    // 안쪽 점선(속도에 맞춰 흐름)
    for (side in listOf(-1f, 1f)) {
        var d0 = 1f + ((2f - (phase % 2f)) % 2f) - 2f
        while (d0 < far) {
            val dA = max(d0, 1f)
            val dB = d0 + 0.9f
            if (dB > 1f) drawPath(band(side - 0.045f, side + 0.045f, dA, dB, steps = 6), Color.White)
            d0 += 2f
        }
    }
}

/**
 * 위에서 비스듬히 본 패스트백 세단(일반형, 특정 차종 아님).
 * 바퀴·후미등·번호판·사이드미러 없이 차체 면의 굴곡(뒤 펜더-허리-앞 펜더), 유리 지붕, 앞유리 경계,
 * 트렁크 립과 보닛 라인 같은 최소한의 선으로만 입체감을 낸다.
 */
private fun DrawScope.drawSedan(center: Offset, cw: Float, curve: Float, time: Float, playing: Boolean) {
    val ch = cw * 2.0f // 위에서 보므로 앞뒤 길이가 길게 보인다
    val bounce = if (playing) sin(time * 2f * PI.toFloat() * 1.1f) * ch * 0.003f else 0f
    val cx = center.x
    val bottom = center.y + ch * 0.42f + bounce
    fun yAt(f: Float) = bottom - ch * f
    fun x(f: Float) = cx + cw * f

    // 바닥 그림자: 넓게 번지는 그림자 + 차체 바로 아래 짙은 접지 그림자(빛은 왼쪽 위에서 온다고 가정)
    drawOval(
        Brush.radialGradient(
            0f to Color(0xFF0B1F3A).copy(alpha = 0.18f),
            0.7f to Color(0xFF0B1F3A).copy(alpha = 0.06f),
            1f to Color.Transparent,
            center = Offset(x(0.03f), yAt(0.48f)),
            radius = cw * 0.58f,
        ),
        topLeft = Offset(x(-0.53f), yAt(1.00f)),
        size = Size(cw * 1.08f, ch * 1.02f),
    )

    rotate(curve * 4f, pivot = Offset(cx, yAt(0.5f))) {
        // 차체: 넓은 뒤 펜더 → 살짝 잘록한 허리 → 앞 펜더 → 둥근 앞코
        val body = Path().apply {
            moveTo(x(-0.34f), yAt(0f))
            cubicTo(x(-0.45f), yAt(0f), x(-0.49f), yAt(0.04f), x(-0.495f), yAt(0.13f))
            cubicTo(x(-0.50f), yAt(0.24f), x(-0.47f), yAt(0.40f), x(-0.455f), yAt(0.50f))
            cubicTo(x(-0.445f), yAt(0.58f), x(-0.475f), yAt(0.66f), x(-0.47f), yAt(0.76f))
            cubicTo(x(-0.465f), yAt(0.88f), x(-0.40f), yAt(0.985f), x(-0.26f), yAt(1.0f))
            cubicTo(x(-0.12f), yAt(1.012f), x(0.12f), yAt(1.012f), x(0.26f), yAt(1.0f))
            cubicTo(x(0.40f), yAt(0.985f), x(0.465f), yAt(0.88f), x(0.47f), yAt(0.76f))
            cubicTo(x(0.475f), yAt(0.66f), x(0.445f), yAt(0.58f), x(0.455f), yAt(0.50f))
            cubicTo(x(0.47f), yAt(0.40f), x(0.50f), yAt(0.24f), x(0.495f), yAt(0.13f))
            cubicTo(x(0.49f), yAt(0.04f), x(0.45f), yAt(0f), x(0.34f), yAt(0f))
            cubicTo(x(0.15f), yAt(-0.008f), x(-0.15f), yAt(-0.008f), x(-0.34f), yAt(0f))
            close()
        }
        // 펄 화이트: 앞쪽이 밝고 뒤로 갈수록 은은한 회색, 양옆은 곡면이 꺾이며 어두워진다
        drawPath(body, Brush.verticalGradient(listOf(Color.White, Color(0xFFF4F6F9), Color(0xFFE1E6EC)), startY = yAt(1f), endY = bottom))
        drawPath(
            body,
            Brush.horizontalGradient(
                0f to Color(0xFF66768C).copy(alpha = 0.34f),
                0.16f to Color.Transparent,
                0.84f to Color.Transparent,
                1f to Color(0xFF66768C).copy(alpha = 0.40f),
                startX = x(-0.5f),
                endX = x(0.5f),
            ),
        )
        // 왼쪽 어깨선에 맺히는 빛
        drawPath(
            body,
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.10f to Color.White.copy(alpha = 0.75f),
                0.22f to Color.Transparent,
                startX = x(-0.5f),
                endX = x(0.5f),
            ),
        )
        drawPath(body, Color(0xFFBCC5D1), style = Stroke(width = cw * 0.006f))

        // 트렁크 립(덕테일): 밝은 선 + 아래 얇은 그늘
        drawPath(
            Path().apply {
                moveTo(x(-0.33f), yAt(0.105f))
                quadraticBezierTo(cx, yAt(0.085f), x(0.33f), yAt(0.105f))
            },
            Color(0xFFC9D1DC),
            style = Stroke(width = cw * 0.008f, cap = StrokeCap.Round),
        )
        drawPath(
            Path().apply {
                moveTo(x(-0.32f), yAt(0.115f))
                quadraticBezierTo(cx, yAt(0.095f), x(0.32f), yAt(0.115f))
            },
            Color.White,
            style = Stroke(width = cw * 0.010f, cap = StrokeCap.Round),
        )

        // 유리: 뒷유리부터 유리 지붕까지 하나로 이어진 캐노피
        val canopy = Path().apply {
            moveTo(x(-0.27f), yAt(0.20f))
            cubicTo(x(-0.34f), yAt(0.24f), x(-0.36f), yAt(0.40f), x(-0.355f), yAt(0.50f))
            cubicTo(x(-0.35f), yAt(0.62f), x(-0.34f), yAt(0.74f), x(-0.29f), yAt(0.80f))
            cubicTo(x(-0.18f), yAt(0.845f), x(0.18f), yAt(0.845f), x(0.29f), yAt(0.80f))
            cubicTo(x(0.34f), yAt(0.74f), x(0.35f), yAt(0.62f), x(0.355f), yAt(0.50f))
            cubicTo(x(0.36f), yAt(0.40f), x(0.34f), yAt(0.24f), x(0.27f), yAt(0.20f))
            cubicTo(x(0.14f), yAt(0.18f), x(-0.14f), yAt(0.18f), x(-0.27f), yAt(0.20f))
            close()
        }
        drawPath(
            canopy,
            Brush.verticalGradient(
                listOf(Color(0xFF4A5B71), Color(0xFF1A2330), Color(0xFF141C27), Color(0xFF2C394B)),
                startY = yAt(0.845f),
                endY = yAt(0.18f),
            ),
        )
        // 하늘이 비친 부드러운 반사(왼쪽 위에서 안쪽으로 사라짐)
        drawPath(
            canopy,
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.20f),
                0.5f to Color.White.copy(alpha = 0.04f),
                1f to Color.Transparent,
                start = Offset(x(-0.36f), yAt(0.84f)),
                end = Offset(x(0.10f), yAt(0.40f)),
            ),
        )
        drawPath(canopy, Color.White.copy(alpha = 0.18f), style = Stroke(width = cw * 0.006f))
        // 앞유리 위쪽 경계(차체색 띠)
        drawPath(
            Path().apply {
                moveTo(x(-0.345f), yAt(0.69f))
                quadraticBezierTo(cx, yAt(0.715f), x(0.345f), yAt(0.69f))
            },
            Color(0xFFE6EBF1),
            style = Stroke(width = cw * 0.016f, cap = StrokeCap.Round),
        )

        // 보닛의 두 줄 캐릭터 라인
        for (side in listOf(-1f, 1f)) {
            drawLine(
                Color(0xFFC3CCD8).copy(alpha = 0.8f),
                start = Offset(x(side * 0.17f), yAt(0.865f)),
                end = Offset(x(side * 0.11f), yAt(0.975f)),
                strokeWidth = cw * 0.005f,
                cap = StrokeCap.Round,
            )
        }
        // 보닛 광택
        drawOval(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.8f), Color.Transparent), center = Offset(x(-0.04f), yAt(0.92f)), radius = cw * 0.26f),
            topLeft = Offset(x(-0.30f), yAt(0.97f)),
            size = Size(cw * 0.52f, ch * 0.09f),
        )
    }
}
