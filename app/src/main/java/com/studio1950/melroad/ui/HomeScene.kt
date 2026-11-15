package com.studio1950.melroad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.studio1950.melroad.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** 배경 이미지(res/drawable-nodpi/home_scene.webp) 속 해와 윤슬의 위치(이미지 크기 비율). */
private object HomeSceneGeometry {
    const val SUN_X = 0.133f
    const val SUN_Y = 0.656f
    const val GLITTER_TOP = 0.745f
    const val GLITTER_BOTTOM = 0.86f
}

/**
 * 첫 화면 배경: 해질녘 해안도로 이미지 위에 아주 작은 움직임만 겹친다.
 * - 해가 천천히 숨 쉬듯 밝아졌다 어두워진다.
 * - 해 아래 바다의 윤슬이 반짝인다(위치는 시간·번호로만 정해져 매번 같은 흐름).
 * 장식이라 화면 낭독기에서는 숨기고, 기기에서 애니메이션을 끄면 움직이지 않는다.
 * 화면 비율이 달라도 이미지는 잘라 채우고(cover), 같은 변환으로 겹칠 위치를 계산한다.
 */
@Composable
fun HomeScene(modifier: Modifier = Modifier) {
    val image = ImageBitmap.imageResource(R.drawable.home_scene)
    val reducedMotion = rememberReducedMotion()
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
            }
        }
    }

    Canvas(modifier.clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        val scale = max(w / iw, h / ih)
        val dx = (w - iw * scale) / 2f
        val dy = (h - ih * scale) / 2f
        drawImage(
            image,
            dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()),
            dstSize = IntSize((iw * scale).roundToInt(), (ih * scale).roundToInt()),
        )
        fun px(fx: Float) = dx + fx * iw * scale
        fun py(fy: Float) = dy + fy * ih * scale
        val imageW = iw * scale
        val g = HomeSceneGeometry

        // 해의 숨쉬기
        val breath = if (reducedMotion) 0.5f else 0.5f + 0.5f * sin(time * 2f * PI.toFloat() / 6f)
        val sun = Offset(px(g.SUN_X), py(g.SUN_Y))
        val sunR = imageW * (0.07f + 0.015f * breath)
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFFFF4E0).copy(alpha = 0.18f + 0.14f * breath), Color.Transparent), center = sun, radius = sunR),
            radius = sunR,
            center = sun,
        )

        // 윤슬: 해 아래 바다에서 작은 빛이 하나씩 반짝였다 사라진다
        if (!reducedMotion) {
            for (i in 0 until 14) {
                val period = 2.2f + (i * 37 % 9) * 0.25f
                val u = (time / period + i * 0.618034f) % 1f
                val a = sin(PI.toFloat() * u).pow(3f) * 0.75f
                val fy = g.GLITTER_TOP + (g.GLITTER_BOTTOM - g.GLITTER_TOP) * (((i * 53) % 17) / 16f)
                // 아래로 갈수록 윤슬 띠가 넓어진다
                val spread = 0.012f + 0.05f * (fy - g.GLITTER_TOP) / (g.GLITTER_BOTTOM - g.GLITTER_TOP)
                val fx = g.SUN_X + spread * ((((i * 29) % 13) / 12f) * 2f - 1f)
                val c = Offset(px(fx), py(fy))
                val r = imageW * 0.006f
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = a), Color(0xFFFFE7C2).copy(alpha = a * 0.35f), Color.Transparent), center = c, radius = r * 3f),
                    radius = r * 3f,
                    center = c,
                )
            }
        }
    }
}
