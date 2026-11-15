package com.studio1950.melroad.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studio1950.melroad.R
import com.studio1950.melroad.ui.theme.ActiveRed
import com.studio1950.melroad.ui.theme.HyundaiBlue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 진행 방향 위쪽 고정 방위계. 다이얼(북 표시)이 돌아가고 가운데 화살표가 늘 진행 방향을 가리킨다.
 * 방향 신호가 있을 때만 호출한다(속도만 있는 경우 방위를 지어내지 않는다).
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
    val shown by animateFloatAsState(unwrapped, spring(stiffness = Spring.StiffnessLow), label = "heading")

    val names = stringArrayResource(R.array.compass_directions)
    val normalized = ((headingDeg % 360f) + 360f) % 360f
    val name = names[((normalized + 22.5f) / 45f).toInt() % names.size]
    val description = stringResource(R.string.compass_desc, name)
    val measurer = rememberTextMeasurer()
    val shape = RoundedCornerShape(40.dp)

    Row(
        modifier
            .shadow(6.dp, shape, ambientColor = HyundaiBlue.copy(alpha = 0.3f), spotColor = HyundaiBlue.copy(alpha = 0.3f))
            .background(Color.White.copy(alpha = 0.92f), shape)
            .border(1.dp, Color(0xFFDCE3EB), shape)
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(60.dp)) {
            val r = size.minDimension / 2f
            val c = center
            drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFE8EDF3)), center = c, radius = r), radius = r)
            drawCircle(Color(0xFFC9D2DC), radius = r - 1.dp.toPx(), style = Stroke(width = 1.2.dp.toPx()))

            // 다이얼: 진행 방향이 위로 오도록 -heading만큼 돌린다.
            rotate(-shown, pivot = c) {
                for (i in 0 until 12) {
                    val a = (i * 30f - 90f) * (PI.toFloat() / 180f)
                    val outer = r * 0.86f
                    val inner = if (i % 3 == 0) r * 0.70f else r * 0.76f
                    drawLine(
                        Color(0xFF9AA7B6),
                        Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
                        Offset(c.x + cos(a) * outer, c.y + sin(a) * outer),
                        strokeWidth = if (i % 3 == 0) 1.6.dp.toPx() else 1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                val label = measurer.measure("N", TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ActiveRed))
                drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - r * 0.52f - label.size.height / 2f))
            }

            // 고정 화살표: 늘 위쪽 = 진행 방향
            val arrow = Path().apply {
                moveTo(c.x, c.y - r * 0.30f)
                lineTo(c.x + r * 0.20f, c.y + r * 0.22f)
                lineTo(c.x, c.y + r * 0.10f)
                lineTo(c.x - r * 0.20f, c.y + r * 0.22f)
                close()
            }
            drawPath(arrow, Brush.verticalGradient(listOf(Color(0xFF1B5BA6), HyundaiBlue), startY = c.y - r * 0.3f, endY = c.y + r * 0.22f))
        }
        Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = HyundaiBlue)
    }
}
