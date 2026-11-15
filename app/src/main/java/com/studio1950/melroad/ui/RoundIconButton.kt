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

enum class RoundIcon { PLAY, PLAYLIST, INFO }

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
                1.5.dp,
                if (primary) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.5f), Color.Transparent)) else Brush.verticalGradient(listOf(Color.White, Color(0xFFCBD5E1))),
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
                RoundIcon.PLAYLIST -> {
                    val w = s * 0.055f
                    drawLine(tint, p(0.27f, 0.34f), p(0.70f, 0.34f), strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(tint, p(0.27f, 0.48f), p(0.70f, 0.48f), strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(tint, p(0.27f, 0.62f), p(0.50f, 0.62f), strokeWidth = w, cap = StrokeCap.Round)
                    val tri = Path().apply {
                        moveTo(p(0.59f, 0.56f).x, p(0.59f, 0.56f).y)
                        lineTo(p(0.77f, 0.665f).x, p(0.77f, 0.665f).y)
                        lineTo(p(0.59f, 0.77f).x, p(0.59f, 0.77f).y)
                        close()
                    }
                    drawPath(tri, ActiveBlue)
                    drawPath(tri, ActiveBlue, style = Stroke(width = s * 0.03f, join = StrokeJoin.Round))
                }
                RoundIcon.INFO -> {
                    drawCircle(tint, radius = s * 0.055f, center = p(0.5f, 0.31f))
                    drawRoundRect(
                        tint,
                        topLeft = p(0.455f, 0.43f),
                        size = Size(s * 0.09f, s * 0.30f),
                        cornerRadius = CornerRadius(s * 0.045f),
                    )
                }
            }
        }
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
