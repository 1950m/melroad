package com.studio1950.melroad.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.sin

/*
 * 멜로드 심볼(자체 디자인): 소리 물결로 그린 지평선 위로 떠오르는 해, 그 해를 향해 뻗은 길.
 * '함께 달리는 길이 음악이 되어 떠오른다'는 뜻. 런처 아이콘(res/drawable/ic_launcher_foreground.xml)도 같은 형태다.
 */

/** 심볼 안의 기준 좌표(0..1, 원형 배지 기준). 런처 아이콘 벡터와 같은 값을 쓴다. */
internal object MarkGeometry {
    const val HORIZON_Y = 0.53f
    const val SUN_R = 0.19f
    const val WAVE_LEFT = 0.15f
    const val WAVE_RIGHT = 0.85f
    const val WAVE_AMP = 0.024f
    const val ROAD_TOP_HALF = 0.035f
    const val ROAD_BOTTOM_Y = 0.84f
    const val ROAD_BOTTOM_HALF = 0.18f

    /** 지평선 소리 물결: 양 끝으로 갈수록 잦아드는 3주기 물결 */
    fun waveY(x: Float): Float {
        val u = (x - WAVE_LEFT) / (WAVE_RIGHT - WAVE_LEFT)
        val taper = sin(PI.toFloat() * u)
        return HORIZON_Y + WAVE_AMP * taper * sin(2f * PI.toFloat() * 3f * u)
    }
}

/** 원형 배지 심볼 */
@Composable
fun MelroadMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .size(size)
            .shadow(size * 0.18f, CircleShape, ambientColor = HyundaiBlue, spotColor = HyundaiBlue)
            .clearAndSetSemantics { },
    ) { drawMelroadMark() }
}

private fun DrawScope.drawMelroadMark() {
    val s = size.minDimension
    val g = MarkGeometry
    fun p(x: Float, y: Float) = Offset(x * s, y * s)

    // 배지: 새벽 하늘처럼 위가 밝은 현대 블루
    drawCircle(Brush.verticalGradient(listOf(Color(0xFF1B5FA8), HyundaiBlue, Color(0xFF001A3A)), startY = 0f, endY = s))
    drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), startY = 0f, endY = s * 0.5f))

    // 해: 지평선 위 반원 + 번지는 빛
    val sun = p(0.5f, g.HORIZON_Y)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), center = sun, radius = s * 0.36f), radius = s * 0.36f, center = sun)
    val half = Path().apply {
        arcTo(Rect(sun, s * g.SUN_R), 180f, 180f, forceMoveTo = true)
        close()
    }
    drawPath(half, Brush.radialGradient(listOf(Color.White, Color(0xFFCFEFF8), ActiveBlue), center = sun, radius = s * g.SUN_R))

    // 소리 물결 지평선
    val wave = Path()
    val steps = 48
    for (i in 0..steps) {
        val x = g.WAVE_LEFT + (g.WAVE_RIGHT - g.WAVE_LEFT) * i / steps
        val pt = p(x, g.waveY(x))
        if (i == 0) wave.moveTo(pt.x, pt.y) else wave.lineTo(pt.x, pt.y)
    }
    drawPath(wave, Color.White, style = Stroke(width = s * 0.028f, cap = StrokeCap.Round))

    // 해를 향해 뻗은 길(양쪽 가장자리 + 가운데 점선)
    val topY = g.HORIZON_Y + 0.045f
    for (side in listOf(-1f, 1f)) {
        drawLine(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.95f)), startY = topY * s, endY = g.ROAD_BOTTOM_Y * s),
            p(0.5f + side * g.ROAD_TOP_HALF, topY),
            p(0.5f + side * g.ROAD_BOTTOM_HALF, g.ROAD_BOTTOM_Y),
            strokeWidth = s * 0.03f,
            cap = StrokeCap.Round,
        )
    }
    val dashes = listOf(0.63f to 0.67f, 0.71f to 0.76f, 0.80f to 0.86f)
    dashes.forEachIndexed { i, (a, b) ->
        drawLine(ActiveBlue.copy(alpha = 0.95f), p(0.5f, a), p(0.5f, b), strokeWidth = s * (0.018f + 0.008f * i), cap = StrokeCap.Round)
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
