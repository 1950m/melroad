package com.studio1950.melroad.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.studio1950.melroad.ui.theme.ActiveBlue
import com.studio1950.melroad.ui.theme.HyundaiBlue
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/*
 * 멜로드 심볼(자체 디자인): 빛을 향해 멀어지는 길과, 그 길 한가운데를 따라 흐르는 선율 물결.
 * 가까울수록 물결이 크고 굵으며 멀어질수록 잦아들어 길 끝의 빛으로 이어진다.
 * 런처 아이콘(res/drawable/ic_launcher_foreground.xml)도 같은 형태를 쓴다.
 */

/** 심볼 안의 기준 좌표(0..1). 런처 아이콘 벡터와 같은 값을 쓴다. */
private object MarkGeometry {
    const val NEAR_Y = 0.80f
    const val FAR_Y = 0.33f
    const val LIGHT_Y = 0.28f
    const val NEAR_HALF = 0.25f
    const val FAR_HALF = 0.02f

    /** t: 0(가까움)..1(멂) → 선율 물결 위의 점(x, y) */
    fun wave(t: Float): Pair<Float, Float> {
        val y = NEAR_Y + 0.02f - (NEAR_Y + 0.02f - FAR_Y - 0.03f) * t
        val amplitude = 0.10f * (1f - t).pow(1.3f)
        val x = 0.5f + amplitude * sin(2f * PI.toFloat() * 2.1f * t.pow(0.8f))
        return x to y
    }
}

/** 둥근 사각형 바탕 위에 길·선율·빛을 그린 심볼 */
@Composable
fun MelroadMark(size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.24f)
    Canvas(
        modifier
            .size(size)
            .shadow(size * 0.16f, shape, ambientColor = HyundaiBlue, spotColor = HyundaiBlue)
            .clearAndSetSemantics { },
    ) { drawMelroadMark() }
}

private fun DrawScope.drawMelroadMark() {
    val s = size.minDimension
    fun p(x: Float, y: Float) = Offset(x * s, y * s)

    // 바탕: 현대 블루 깊이감 + 위쪽 은은한 광택
    drawRoundRect(
        Brush.linearGradient(listOf(Color(0xFF0B4F96), HyundaiBlue, Color(0xFF001A3A)), start = Offset.Zero, end = Offset(s, s)),
        cornerRadius = CornerRadius(s * 0.24f),
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), startY = 0f, endY = s * 0.5f),
        cornerRadius = CornerRadius(s * 0.24f),
    )

    // 길(면 + 양쪽 가장자리)
    val g = MarkGeometry
    val road = Path().apply {
        moveTo(p(0.5f - g.NEAR_HALF, g.NEAR_Y).x, p(0f, g.NEAR_Y).y)
        lineTo(p(0.5f - g.FAR_HALF, g.FAR_Y).x, p(0f, g.FAR_Y).y)
        lineTo(p(0.5f + g.FAR_HALF, g.FAR_Y).x, p(0f, g.FAR_Y).y)
        lineTo(p(0.5f + g.NEAR_HALF, g.NEAR_Y).x, p(0f, g.NEAR_Y).y)
        close()
    }
    drawPath(road, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.12f)), startY = g.FAR_Y * s, endY = g.NEAR_Y * s))
    for (side in listOf(-1f, 1f)) {
        drawLine(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.9f)), startY = g.FAR_Y * s, endY = g.NEAR_Y * s),
            p(0.5f + side * g.NEAR_HALF, g.NEAR_Y),
            p(0.5f + side * g.FAR_HALF, g.FAR_Y),
            strokeWidth = s * 0.03f,
            cap = StrokeCap.Round,
        )
    }

    // 선율 물결: 가까운 쪽은 굵은 액티브 블루, 멀수록 가늘고 하얗게
    val segments = 40
    for (i in 0 until segments) {
        val t0 = i / segments.toFloat()
        val t1 = (i + 1) / segments.toFloat()
        val (x0, y0) = g.wave(t0)
        val (x1, y1) = g.wave(t1)
        drawLine(
            lerp(ActiveBlue, Color.White, t0.pow(0.7f)),
            p(x0, y0),
            p(x1, y1),
            strokeWidth = s * (0.055f - 0.04f * t0),
            cap = StrokeCap.Round,
        )
    }

    // 길 끝의 빛
    val light = p(0.5f, g.LIGHT_Y)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), center = light, radius = s * 0.14f), radius = s * 0.14f, center = light)
    drawCircle(Color.White, radius = s * 0.035f, center = light)
}

/**
 * 첫 화면 장면 위에 얹는 로고(심볼 + 영문 이름). 처음 나타날 때 아래에서 살짝 떠오르며 서서히 선명해진다.
 * 화면 낭독기에서는 '멜로드' 제목 하나로 읽힌다.
 */
@Composable
fun MelroadLogo(name: String, wordmark: String, modifier: Modifier = Modifier) {
    val reveal = remember { Animatable(0f) }
    val reducedMotion = rememberReducedMotion()
    LaunchedEffect(Unit) {
        if (reducedMotion) reveal.snapTo(1f) else reveal.animateTo(1f, tween(1400, delayMillis = 200, easing = FastOutSlowInEasing))
    }
    Column(
        modifier
            .graphicsLayer {
                alpha = reveal.value
                translationY = (1f - reveal.value) * 18.dp.toPx()
            }
            .clearAndSetSemantics {
                contentDescription = name
                heading()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MelroadMark(88.dp)
        Text(
            wordmark,
            style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 0.32.em),
            fontWeight = FontWeight.SemiBold,
            color = HyundaiBlue,
        )
    }
}
