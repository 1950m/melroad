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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
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

/**
 * 첫 화면 배경의 기하 상수. 배경 이미지(res/drawable-nodpi/home_scene.webp)를 만든
 * tools/render_home_scene.py와 같은 값이어야 자동차·빛이 이미지 속 길과 해에 정확히 겹친다.
 */
private object HomeSceneGeometry {
    const val HORIZON_Y = 0.40f
    const val VANISH_X = 0.53f
    const val SUN_X = 0.40f
    const val ROAD_HALF_AT_BOTTOM = 0.40f // 이미지 폭 비율
    const val CENTER_LANE_HALF = 0.30f    // 길 반폭 비율(경로 리본)
    const val CAR_Y = 0.56f

    /** 이미지 세로 위치 y(0..1)에서 길 가운데 x(0..1)와 길 반폭(이미지 폭 비율) */
    fun road(y: Float): Pair<Float, Float> {
        val k = ((y - HORIZON_Y) / (1f - HORIZON_Y)).coerceAtLeast(0f)
        return (VANISH_X + (0.5f - VANISH_X) * k) to ROAD_HALF_AT_BOTTOM * k
    }
}

/**
 * 첫 화면 배경: 해질녘 제주 해안도로 이미지(자체 렌더링) 위에 움직이는 요소를 겹친다.
 * - 해가 아주 천천히 숨 쉬듯 밝아졌다 어두워진다.
 * - 길 끝에서 생긴 빛망울이 길을 따라 다가와 스쳐 간다.
 * - 자동차(모형)와 앞을 비추는 전조등 빛.
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
        val sun = Offset(px(g.SUN_X), py(g.HORIZON_Y))
        val sunR = imageW * (0.10f + 0.02f * breath)
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFFFF1DE).copy(alpha = 0.10f + 0.10f * breath), Color.Transparent), center = sun, radius = sunR),
            radius = sunR,
            center = sun,
        )

        // 길을 따라 다가오는 빛망울
        if (!reducedMotion) drawRoadMotes(time, ::px, ::py, imageW)

        // 자동차와 전조등
        val (cx, half) = g.road(g.CAR_Y)
        val laneHalfPx = half * g.CENTER_LANE_HALF * imageW
        val carWidth = laneHalfPx * 2f * 0.50f
        val carCenter = Offset(px(cx), py(g.CAR_Y))
        drawHeadlights(carCenter, carWidth, ::px, ::py, imageW)
        drawSedan(carCenter, carWidth, curve = 0f, time = if (reducedMotion) 0f else time, playing = !reducedMotion)
    }
}

/** 차 앞쪽(위)으로 길을 따라 퍼지는 두 줄기 전조등 빛. 멀어질수록 옅어진다. */
private fun DrawScope.drawHeadlights(car: Offset, cw: Float, px: (Float) -> Float, py: (Float) -> Float, imageW: Float) {
    val g = HomeSceneGeometry
    val frontY = car.y - cw * 1.0f
    val farY = py(g.HORIZON_Y + (g.CAR_Y - g.HORIZON_Y) * 0.35f)
    for (side in listOf(-1f, 1f)) {
        val beam = Path().apply {
            moveTo(car.x + side * cw * 0.22f, frontY)
            lineTo(car.x + side * cw * 0.38f, frontY)
            lineTo(car.x + side * cw * 0.30f + side * imageW * 0.012f, farY)
            lineTo(car.x + side * cw * 0.02f, farY)
            close()
        }
        drawPath(
            beam,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color(0xFFEAF6FF).copy(alpha = 0.22f)), startY = farY, endY = frontY),
        )
    }
}

/** 길 끝(소실점)에서 생긴 작은 빛이 길 양옆을 따라 다가오며 커지고 사라진다. 위치는 시간·번호로만 정해진다. */
private fun DrawScope.drawRoadMotes(time: Float, px: (Float) -> Float, py: (Float) -> Float, imageW: Float) {
    val g = HomeSceneGeometry
    for (i in 0 until 10) {
        val period = 7f + (i * 37 % 6)
        val u = (time / period + i * 0.618034f) % 1f
        val y = g.HORIZON_Y + (0.98f - g.HORIZON_Y) * u.pow(1.8f)
        val (cx, half) = g.road(y)
        val side = if (i % 2 == 0) -1f else 1f
        val lateral = 0.75f + ((i * 53) % 10) / 10f * 0.9f // 길 반폭 기준(1보다 크면 길 밖, 바다·들 위)
        val x = cx + side * half * lateral
        val r = imageW * (0.002f + 0.010f * u)
        val a = sin(PI.toFloat() * u).pow(1.4f) * 0.55f
        val c = Offset(px(x), py(y))
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = a), Color(0xFF9FD8F0).copy(alpha = a * 0.3f), Color.Transparent), center = c, radius = r * 2.5f),
            radius = r * 2.5f,
            center = c,
        )
    }
}
