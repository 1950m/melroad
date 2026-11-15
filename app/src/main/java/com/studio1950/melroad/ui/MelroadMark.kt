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
 * 멜로드 심볼(자체 디자인): "흐르는 선율". 남색 둥근 사각 배지 안에 가는 선율 세 가닥이 흐른다.
 * 첫 화면의 시작 버튼(움직이는 선율)과 같은 모양이고, 런처 아이콘(res/drawable/ic_launcher_foreground.xml)도 같은 형태다.
 */

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
    val corner = CornerRadius(s * 0.28f)
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF1F66B3), HyundaiBlue, Color(0xFF001A3A)), start = Offset(0f, 0f), end = Offset(s * 0.4f, s)), cornerRadius = corner)
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), startY = 0f, endY = s * 0.5f), cornerRadius = corner)
    // 작은 배지에서도 보이도록 진폭과 굵기를 조금 키운다
    drawMelodyStrands(s, MELODY_REST_PHASE, Color.White, ampScale = 1.35f, widthScale = 1.9f)
}

/**
 * 첫 화면 왼쪽 위 브랜드(심볼 + 영문 이름). 처음 나타날 때 살짝 떠오르며 서서히 선명해진다.
 * 화면 낭독기에서는 '멜로드' 제목 하나로 읽힌다.
 */
@Composable
fun MelroadBrand(name: String, wordmark: String, modifier: Modifier = Modifier, markSize: Dp = 64.dp, textColor: Color = HyundaiBlue) {
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
            color = textColor,
        )
    }
}
