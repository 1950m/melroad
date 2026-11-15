package com.studio1950.melroad.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.studio1950.melroad.ui.theme.ActiveBlue
import com.studio1950.melroad.ui.theme.HyundaiBlue
import com.studio1950.melroad.ui.theme.HyundaiBlueLight
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

enum class RoundIcon { PLAY, MELODY, PLAYLIST, INFO }

/**
 * 글자 없는 원형 아이콘 버튼. 화면에는 아이콘만 보이고, 화면 낭독기는 [description]을 '버튼'으로 읽는다.
 * 크기는 차량 터치 기준(48dp)보다 넉넉하게 쓴다.
 */
@Composable
fun RoundIconButton(
    icon: RoundIcon,
    description: String,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "press")
    val fill = if (primary) {
        Brush.verticalGradient(listOf(HyundaiBlueLight, HyundaiBlue, Color(0xFF001A3A)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.96f), Color(0xFFEFF3F8).copy(alpha = 0.92f)))
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (primary) size * 0.16f else size * 0.10f, CircleShape, ambientColor = HyundaiBlue, spotColor = HyundaiBlue)
            .clip(CircleShape)
            .background(fill)
            .border(
                if (primary) 2.5.dp else 1.5.dp,
                // 어두운 배경 위에서도 윤곽이 보이도록 큰 버튼은 밝은 테두리를 두른다
                if (primary) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.9f), Color(0xFF9FD8F0).copy(alpha = 0.55f))) else Brush.verticalGradient(listOf(Color.White, Color(0xFFCBD5E1))),
                CircleShape,
            )
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (primary) Color.White else HyundaiBlue
        if (icon == RoundIcon.MELODY) {
            FlowingMelody(tint)
            return@Box
        }
        Canvas(Modifier.fillMaxSize()) {
            val s = this.size.minDimension
            fun p(x: Float, y: Float) = Offset(x * s, y * s)
            when (icon) {
                RoundIcon.PLAY -> {
                    // 시각적 무게 중심을 맞추려고 삼각형을 오른쪽으로 조금 옮긴다
                    val tri = Path().apply {
                        moveTo(p(0.41f, 0.31f).x, p(0.41f, 0.31f).y)
                        lineTo(p(0.71f, 0.5f).x, p(0.71f, 0.5f).y)
                        lineTo(p(0.41f, 0.69f).x, p(0.41f, 0.69f).y)
                        close()
                    }
                    drawPath(tri, tint)
                    drawPath(tri, tint, style = Stroke(width = s * 0.05f, join = StrokeJoin.Round))
                }
                RoundIcon.MELODY -> Unit
                RoundIcon.PLAYLIST -> {
                    // 뮤직박스: 가는 세 줄(저장된 곡 목록)과 작은 재생 표시. 모든 아이콘을 같은 남색·같은 굵기로 맞춘다.
                    val w = s * 0.034f
                    drawLine(tint, p(0.28f, 0.36f), p(0.72f, 0.36f), strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(tint, p(0.28f, 0.50f), p(0.72f, 0.50f), strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(tint, p(0.28f, 0.64f), p(0.50f, 0.64f), strokeWidth = w, cap = StrokeCap.Round)
                    val tri = Path().apply {
                        moveTo(p(0.60f, 0.585f).x, p(0.60f, 0.585f).y)
                        lineTo(p(0.735f, 0.665f).x, p(0.735f, 0.665f).y)
                        lineTo(p(0.60f, 0.745f).x, p(0.60f, 0.745f).y)
                        close()
                    }
                    drawPath(tri, tint)
                    drawPath(tri, tint, style = Stroke(width = s * 0.02f, join = StrokeJoin.Round))
                }
                RoundIcon.INFO -> {
                    drawCircle(tint, radius = s * 0.042f, center = p(0.5f, 0.32f))
                    drawRoundRect(
                        tint,
                        topLeft = p(0.467f, 0.43f),
                        size = Size(s * 0.066f, s * 0.28f),
                        cornerRadius = CornerRadius(s * 0.033f),
                    )
                }
            }
        }
    }
}

/**
 * '흐르는 선율' 아이콘: 가는 선 세 가닥이 서로 조금씩 엇갈리며 왼쪽에서 오른쪽으로 천천히 흘러간다.
 * 양 끝은 한 점으로 모이며 투명해져 선이 끊긴 느낌 없이 나타나고 사라진다. 물결의 높이도 아주 천천히 숨 쉰다.
 * 애니메이션 끄기 설정이면 멈춘 모양으로 그린다. 멜로드 심볼·런처 아이콘도 같은 세 가닥(멈춘 모양)이다.
 */
@Composable
private fun FlowingMelody(tint: Color) {
    val reducedMotion = rememberReducedMotion()
    val phase by rememberInfiniteTransition(label = "melody").animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(3_600, easing = LinearEasing)),
        label = "phase",
    )
    Canvas(Modifier.fillMaxSize()) {
        drawMelodyStrands(size.minDimension, if (reducedMotion) MELODY_REST_PHASE else phase, tint)
    }
}

/** 멈춘 모양에 쓰는 위상(심볼·런처 아이콘·애니메이션 끄기). */
internal const val MELODY_REST_PHASE = 0.4f

/** 가닥별 (진폭, 굵기, 불투명도, 위상 지연) — 크기 s 기준 비율 */
private val MELODY_STRANDS = listOf(
    floatArrayOf(0.095f, 0.020f, 1.00f, 0.0f),
    floatArrayOf(0.070f, 0.012f, 0.70f, 0.9f),
    floatArrayOf(0.050f, 0.009f, 0.45f, 1.8f),
)

/** 세 가닥 선율의 t(0..1) 위치 높이(크기 s 기준 비율, 0.5가 가운데). 양 끝은 진폭 0으로 모인다. */
internal fun melodyStrandY(strand: Int, t: Float, phase: Float, ampScale: Float = 1f): Float {
    val st = MELODY_STRANDS[strand]
    val breathe = 0.88f + 0.12f * sin(phase)
    val env = sin(PI.toFloat() * t).let { if (it > 0f) it.pow(0.9f) else 0f }
    return 0.5f + st[0] * ampScale * breathe * env * sin(t * 2f * PI.toFloat() * 1.35f - phase * 2f - st[3])
}

/** 정사각 영역(크기 s)에 세 가닥 선율을 그린다. [widthScale]로 작은 심볼에서는 선을 조금 굵게 한다. */
internal fun DrawScope.drawMelodyStrands(s: Float, phase: Float, tint: Color, ampScale: Float = 1f, widthScale: Float = 1f) {
    val left = 0.20f
    val right = 0.80f
    for (i in MELODY_STRANDS.indices.reversed()) {
        val st = MELODY_STRANDS[i]
        val path = Path()
        val n = 64
        for (k in 0..n) {
            val t = k / n.toFloat()
            val x = s * (left + (right - left) * t)
            val y = s * melodyStrandY(i, t, phase, ampScale)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val c = tint.copy(alpha = st[2])
        drawPath(
            path,
            Brush.horizontalGradient(listOf(c.copy(alpha = 0f), c, c, c.copy(alpha = 0f)), startX = s * left, endX = s * right),
            style = Stroke(width = s * st[1] * widthScale, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** 큰 재생 버튼 뒤에서 아주 천천히 숨 쉬는 빛(애니메이션 끄기 설정이면 멈춘 상태). */
@Composable
fun BreathingGlow(size: Dp, modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    val breath by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_400, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath",
    )
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val b = if (reducedMotion) 0.5f else breath
        val r = this.size.minDimension / 2f * (0.86f + 0.14f * b)
        drawCircle(
            Brush.radialGradient(
                0f to ActiveBlue.copy(alpha = 0.30f + 0.18f * b),
                0.55f to ActiveBlue.copy(alpha = 0.10f),
                1f to Color.Transparent,
                center = center,
                radius = r,
            ),
            radius = r,
        )
    }
}
