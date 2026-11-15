package com.studio1950.melroad.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.studio1950.melroad.R
import com.studio1950.melroad.ui.theme.ActiveRed
import com.studio1950.melroad.ui.theme.HyundaiBlue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 진행 방향 위쪽 고정 방위계(글자 없는 실루엣). 다이얼과 북쪽 바늘이 돌아가고 가운데 화살표가 늘 진행 방향을 가리킨다.
 * 방향 이름은 화면 낭독기 설명으로만 전달한다. 방향 신호가 있을 때만 호출한다(속도만 있으면 방위를 지어내지 않는다).
 *
 * @param headingDeg 0=북, 시계 방향 도
 */
@Composable
fun HeadingCompass(headingDeg: Float, modifier: Modifier = Modifier) {
    // 359°→1°처럼 북을 지날 때 다이얼이 거꾸로 한 바퀴 돌지 않도록 가장 짧은 방향으로 각도를 이어 붙인다.
    var unwrapped by remember { mutableFloatStateOf(headingDeg) }
    LaunchedEffect(headingDeg) {
        val current = ((unwrapped % 360f) + 360f) % 360f
        var delta = headingDeg - current
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        unwrapped += delta
    }
    val shown by animateFloatAsState(unwrapped, spring(stiffness = Spring.StiffnessVeryLow), label = "heading")

    val names = stringArrayResource(R.array.compass_directions)
    val normalized = ((headingDeg % 360f) + 360f) % 360f
    val name = names[((normalized + 22.5f) / 45f).toInt() % names.size]
    val description = stringResource(R.string.compass_desc, name)

    Canvas(
        modifier
            .size(68.dp)
            .shadow(10.dp, CircleShape, ambientColor = HyundaiBlue.copy(alpha = 0.35f), spotColor = HyundaiBlue.copy(alpha = 0.35f))
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val r = size.minDimension / 2f
        val c = center
        // 유리 원판: 위가 밝고 아래로 은은하게 푸른 빛
        drawCircle(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.96f), Color(0xFFE4ECF4).copy(alpha = 0.92f))), radius = r)
        drawCircle(Brush.verticalGradient(listOf(Color.White, Color(0xFFC9D4E0))), radius = r - 0.75.dp.toPx(), style = Stroke(width = 1.5.dp.toPx()))

        rotate(-shown, pivot = c) {
            for (i in 0 until 24) {
                val a = (i * 15f - 90f) * (PI.toFloat() / 180f)
                val major = i % 6 == 0
                val outer = r * 0.84f
                val inner = if (major) r * 0.68f else r * 0.76f
                drawLine(
                    Color(0xFF9FB0C3).copy(alpha = if (major) 0.95f else 0.55f),
                    Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
                    Offset(c.x + cos(a) * outer, c.y + sin(a) * outer),
                    strokeWidth = if (major) 1.6.dp.toPx() else 0.9.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // 북쪽 바늘(빨간 삼각형)
            val needle = Path().apply {
                moveTo(c.x, c.y - r * 0.86f)
                lineTo(c.x + r * 0.11f, c.y - r * 0.60f)
                lineTo(c.x - r * 0.11f, c.y - r * 0.60f)
                close()
            }
            drawPath(needle, ActiveRed)
        }

        // 고정 화살표: 늘 위쪽 = 진행 방향
        val arrow = Path().apply {
            moveTo(c.x, c.y - r * 0.36f)
            lineTo(c.x + r * 0.24f, c.y + r * 0.26f)
            lineTo(c.x, c.y + r * 0.12f)
            lineTo(c.x - r * 0.24f, c.y + r * 0.26f)
            close()
        }
        drawPath(arrow, Brush.verticalGradient(listOf(Color(0xFF2A6BB8), HyundaiBlue), startY = c.y - r * 0.36f, endY = c.y + r * 0.26f))
        drawCircle(Color.White.copy(alpha = 0.9f), radius = r * 0.05f, center = Offset(c.x, c.y + r * 0.02f))
    }
}
