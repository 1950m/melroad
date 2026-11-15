package com.studio1950.melroad.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import kotlin.math.pow

/*
 * 멜로드 심볼(자체 디자인): "소리로 만든 길".
 * 길 가장자리 안에서 길이가 조금씩 다른 소리 막대(이퀄라이저)가 원근으로 쌓여 멀어지는 길이 되고, 그 끝에 빛 하나가 맺힌다.
 * 가까운 막대는 굵고 넓게, 먼 막대는 가늘고 좁게. 런처 아이콘(res/drawable/ic_launcher_foreground.xml)도 같은 형태다.
 */

/** 심볼 안의 기준 좌표(0..1, 둥근 사각 배지 기준). 런처 아이콘 벡터와 같은 값을 쓴다. */
internal object MarkGeometry {
    const val BARS = 6
    const val NEAR_Y = 0.79f
    const val FAR_Y = 0.37f
    const val LIGHT_Y = 0.255f
    const val LIGHT_R = 0.048f

    // 길 가장자리: 아래(가까움)는 넓고 위(빛 쪽)로 모인다
    const val EDGE_BOTTOM_Y = 0.85f
    const val EDGE_TOP_Y = 0.31f
    const val EDGE_NEAR_HALF = 0.40f
    const val EDGE_FAR_HALF = 0.03f

    /** 막대마다 소리 크기처럼 달라지는 길이 비율(길 폭 안에서만) */
    val LEVELS = floatArrayOf(1.0f, 0.84f, 0.96f, 0.78f, 0.92f, 1.0f)

    fun edgeHalf(y: Float): Float =
        EDGE_FAR_HALF + (EDGE_NEAR_HALF - EDGE_FAR_HALF) * (y - EDGE_TOP_Y) / (EDGE_BOTTOM_Y - EDGE_TOP_Y)

    /** i번째 막대(0=가장 가까움): (중심 y, 반폭, 두께) */
    fun bar(i: Int): Triple<Float, Float, Float> {
        val t = i / (BARS - 1f)
        val y = FAR_Y + (NEAR_Y - FAR_Y) * (1f - t).pow(1.3f)
        val half = edgeHalf(y) * 0.78f * LEVELS[i]
        val thick = 0.022f + 0.036f * (1f - t)
        return Triple(y, half, thick)
    }
}

/** 둥근 사각 배지 심볼 */
@Composable
fun MelroadMark(size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.28f)
    Canvas(
        modifier
            .size(size)
            .shadow(size * 0.18f, shape, ambientColor = HyundaiBlue, spotColor = HyundaiBlue)
            .clearAndSetSemantics { },
    ) { drawMelroadMark() }
}

private fun DrawScope.drawMelroadMark() {
    val s = size.minDimension
    val g = MarkGeometry
    fun p(x: Float, y: Float) = Offset(x * s, y * s)

    // 배지: 위가 밝은 현대 블루 + 위쪽 광택
    val corner = CornerRadius(s * 0.28f)
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF1F66B3), HyundaiBlue, Color(0xFF001A3A)), start = Offset(0f, 0f), end = Offset(s * 0.4f, s)), cornerRadius = corner)
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), startY = 0f, endY = s * 0.5f), cornerRadius = corner)

    // 길 끝의 빛
    val light = p(0.5f, g.LIGHT_Y)
    drawCircle(Brush.radialGradient(listOf(ActiveBlue.copy(alpha = 0.55f), Color.Transparent), center = light, radius = s * 0.17f), radius = s * 0.17f, center = light)
    drawCircle(Color.White, radius = s * g.LIGHT_R, center = light)

    // 길 가장자리(옅은 선)
    for (side in listOf(-1f, 1f)) {
        drawLine(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.30f)), startY = g.EDGE_TOP_Y * s, endY = g.EDGE_BOTTOM_Y * s),
            p(0.5f + side * g.EDGE_FAR_HALF, g.EDGE_TOP_Y),
            p(0.5f + side * g.EDGE_NEAR_HALF, g.EDGE_BOTTOM_Y),
            strokeWidth = s * 0.016f,
            cap = StrokeCap.Round,
        )
    }

    // 소리 막대로 이루어진 길(먼 것부터 그린다)
    for (i in g.BARS - 1 downTo 0) {
        val (y, half, thick) = g.bar(i)
        val t = i / (g.BARS - 1f)
        drawLine(
            lerp(Color.White, Color(0xFF7FD4E8), t * 0.8f),
            p(0.5f - half, y),
            p(0.5f + half, y),
            strokeWidth = s * thick,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * 첫 화면 왼쪽 위 브랜드(심볼 + 영문 이름). 처음 나타날 때 살짝 떠오르며 서서히 선명해진다.
 * 화면 낭독기에서는 '멜로드' 제목 하나로 읽힌다.
 */
@Composable
fun MelroadBrand(name: String, wordmark: String, modifier: Modifier = Modifier, markSize: Dp = 64.dp) {
    val reducedMotion = rememberReducedMotion()
    val reveal = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reducedMotion) reveal.snapTo(1f) else reveal.animateTo(1f, tween(1400, delayMillis = 200, easing = FastOutSlowInEasing))
    }
    Row(
        modifier
            .graphicsLayer {
                alpha = reveal.value
                translationY = (1f - reveal.value) * 14.dp.toPx()
            }
            .clearAndSetSemantics {
                contentDescription = name
                heading()
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        MelroadMark(markSize)
        Text(
            wordmark,
            style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 0.30.em),
            fontWeight = FontWeight.SemiBold,
            color = HyundaiBlue,
        )
    }
}
