package com.studio1950.melroad.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.studio1950.melroad.music.DriveSample
import com.studio1950.melroad.ui.theme.ActiveBlue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 장식용 주행 장면: 차 뒤 조금 위에서 바라본 3차선 도로와 세단 뒷모습.
 *
 * 앞길은 실제 신호로 만든다([RoadBuilder]). 진행 방위가 바뀐 만큼 길이 좌우로 굽고, 고도가 바뀐 만큼 오르막·내리막과
 * 고개가 보인다. 시뮬레이션은 앞으로 다가올 굽이까지 그대로 보이고, 실제 주행은 최근 굽이만 이어 그린다.
 * 노면 표시는 실제로 달린 거리만큼만 흘러가므로 일시정지·정차 중에는 멈춘다.
 * 길 안내·지도 기능이 아니며 화면 낭독기에서는 숨긴다. 기기에서 애니메이션을 끄면 빛망울은 그리지 않는다.
 *
 * @param speedKmh [road]가 없을 때 노면 표시가 흐르는 속도
 * @param curve [road]가 없을 때 쓰는 굽이 -1(왼쪽)..1(오른쪽)
 * @param climb [road]가 없을 때 쓰는 오르내림 -1(내리막)..1(오르막)
 * @param playing 음악 재생 중이면 가운데 차선 리본이 은은하게 숨 쉰다
 * @param road 원본 신호와 재생 시각. 있으면 앞길 모양을 신호로 그린다.
 */
@Composable
fun DriveScene(
    speedKmh: Float,
    curve: Float,
    climb: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    road: RoadFeed? = null,
) {
    val curveAnim by animateFloatAsState(curve, spring(stiffness = Spring.StiffnessVeryLow), label = "curve")
    val climbAnim by animateFloatAsState(climb, tween(2500), label = "climb")
    val speedAnim by animateFloatAsState(speedKmh, tween(1500), label = "speed")

    val currentSpeed by rememberUpdatedState(speedAnim)
    val feed by rememberUpdatedState(road)
    val motion = remember { SceneMotion() }
    var time by remember { mutableFloatStateOf(0f) }
    var odometer by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
                    time += dt
                    val f = feed
                    if (f != null) {
                        // 재생 시각이 실제로 흐른 만큼만 나아간다(일시정지 중에는 노면도 멈춘다).
                        val pos = f.position()
                        val dPos = pos - motion.lastPosition
                        if (motion.lastPosition >= 0 && dPos in 1L..1000L) odometer += motion.speedMps * dPos / 1000f
                        motion.lastPosition = pos
                    } else {
                        odometer += currentSpeed / 3.6f * dt
                    }
                }
                last = now
            }
        }
    }

    val reducedMotion = rememberReducedMotion()

    Canvas(modifier.clearAndSetSemantics { }) {
        val f = feed
        val now = f?.position() ?: 0L
        val geo = RoadBuilder.build(motion.window(f, now), now, odometer, curveAnim, climbAnim)
        motion.speedMps = geo.speedMps
        val cam = SceneCamera(size.width, size.height, geo.slope)
        drawSky(cam, geo)
        drawRoad(cam, geo, playing, time)
        drawCar(cam, geo, time, playing)
        if (!reducedMotion) drawLightMotes(size.width, size.height, time, geo.nearCurve, playing)
        drawRect(
            Brush.radialGradient(
                0f to Color.Transparent,
                0.62f to Color.Transparent,
                1f to Color(0xFF16304F).copy(alpha = 0.16f),
                center = Offset(size.width * 0.5f, size.height * 0.55f),
                radius = max(size.width, size.height) * 0.75f,
            ),
        )
    }
}

/** 프레임 사이에 유지하는 값. 신호는 0.4초마다 다시 읽는다(매 프레임 전체 목록을 거르지 않도록). */
private class SceneMotion {
    var lastPosition = -1L
    var speedMps = 0f
    private var fetchedAt = Long.MIN_VALUE
    private var fetchedFeed: RoadFeed? = null
    private var cached: List<DriveSample> = emptyList()

    fun window(feed: RoadFeed?, now: Long): List<DriveSample> {
        if (feed == null) return emptyList()
        if (feed !== fetchedFeed || now < fetchedAt || now - fetchedAt > 400L) {
            cached = feed.samples(now - 6_000L, now + 60_000L)
            fetchedAt = now
            fetchedFeed = feed
        }
        return cached
    }
}

/**
 * 차 뒤 [CAM_BACK]m, 노면 위 [CAM_H]m에서 수평으로 바라보는 카메라. 오르막에서는 시선이 길을 반쯤 따라 들려
 * 다가오는 오르막이 화면 위로 솟아 보이되 화면 밖으로 사라지지는 않는다.
 */
private class SceneCamera(val w: Float, val h: Float, slope: Float) {
    val focal = h * 0.85f
    val horizon = h * 0.36f + focal * slope * 0.5f
    fun depth(f: Float) = f + CAM_BACK
    fun sx(f: Float, l: Float) = w * 0.5f + focal * l / max(depth(f), NEAR)
    fun sy(f: Float, z: Float) = horizon + focal * (CAM_H - z) / max(depth(f), NEAR)

    companion object {
        const val CAM_BACK = 8.5f
        const val CAM_H = 4.2f
        const val NEAR = 0.5f
    }
}

// 도로 치수(m): 가운데 차선에 차를 두고 양옆에 차선 하나씩.
private const val LANE_LINE = 1.75f
private const val EDGE_LINE = 5.25f
private const val ROAD_HALF = 5.9f
private const val LINE_HALF = 0.08f
private const val RIBBON_HALF = 1.45f

private val SkyTop = Color(0xFFBCD3EB)
private val Haze = Color(0xFFF2F1EE)
private val Ground = Color(0xFFC3CFD3)
private val Asphalt = Color(0xFF98A4B1)
private val RidgeFar = Color(0xFFB3C5D8)
private val RidgeNear = Color(0xFF9FB4C9)
private val Reflector = Color(0xFFE9A84C)

/**
 * 멀수록 지평선 안개색으로 녹아든다. 끝까지 하얗게 지우지 않고 [FOG_MAX]에서 멈춰 먼 땅에도 결이 남게 한다.
 */
private fun fogAt(depth: Float) = ((depth - 14f) / 300f).coerceIn(0f, 1f).pow(0.8f) * FOG_MAX

private const val FOG_MAX = 0.6f

private fun floorMod(a: Int, b: Int) = ((a % b) + b) % b

/**
 * 화면 높이 y에서 기본 땅(길의 가장 낮은 높이에 놓인 평지)이 받는 안개. 지평선 위쪽이면 0.
 * 평지에서는 길 옆 땅 띠와 같은 값이 되고, 내리막·급한 굽이 너머로는 골짜기처럼 보인다.
 */
private fun baseFog(cam: SceneCamera, g: RoadGeometry, y: Float): Float {
    val dy = y - cam.horizon
    if (dy < 0.5f) return 0f
    val zRef = min(0f, g.height.minOrNull() ?: 0f)
    return fogAt(cam.focal * (SceneCamera.CAM_H - zRef) / dy)
}

/** 하늘, 지평선의 빛, 먼 산 두 겹. 산은 진행 방위에 따라 옆으로 흘러 차가 도는 것을 느끼게 한다. */
private fun DrawScope.drawSky(cam: SceneCamera, g: RoadGeometry) {
    val w = cam.w
    val h = cam.h
    val hz = cam.horizon
    drawRect(Brush.verticalGradient(0f to SkyTop, 1f to Haze, startY = 0f, endY = hz))
    // 지평선 아래 기본 땅: 길의 가장 낮은 높이에 평평한 땅이 있다고 보고 거리만큼 안개를 입힌다.
    // 내리막이나 급한 굽이 너머로 보이는 골짜기가 가까운 땅 띠와 같은 색으로 자연스럽게 이어진다.
    val stops = Array(10) { k ->
        val t = k / 9f
        val dy = (h - hz) * t * t
        t * t to lerp(Ground, Haze, if (dy < 0.5f) FOG_MAX else baseFog(cam, g, hz + dy))
    }
    drawRect(Brush.verticalGradient(*stops, startY = hz, endY = h), topLeft = Offset(0f, hz), size = Size(w, h - hz))

    val turn = (g.headingDeg ?: 0f) / 360f
    drawRidge(cam, amp = h * 0.075f, period = w * 1.4f, phase = turn * w * 2.4f, seed = 0f, color = RidgeFar.copy(alpha = 0.55f))
    drawRidge(cam, amp = h * 0.045f, period = w * 0.85f, phase = turn * w * 4.0f, seed = 1f, color = RidgeNear.copy(alpha = 0.50f))

    // 길이 사라지는 쪽 지평선에 번지는 빛
    val far = g.count - 1
    val gx = cam.sx(g.forward[far], g.lateral[far]).coerceIn(w * 0.15f, w * 0.85f)
    val r = w * 0.6f
    drawCircle(
        Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.85f),
            0.35f to Color(0xFFF7F1EA).copy(alpha = 0.45f),
            1f to Color.White.copy(alpha = 0f),
            center = Offset(gx, hz),
            radius = r,
        ),
        radius = r,
        center = Offset(gx, hz),
    )
}

private fun DrawScope.drawRidge(cam: SceneCamera, amp: Float, period: Float, phase: Float, seed: Float, color: Color) {
    val tau = 2f * PI.toFloat()
    val p = Path()
    p.moveTo(0f, cam.horizon + 2f)
    val steps = 64
    for (k in 0..steps) {
        val x = cam.w * k / steps
        val u = x + phase
        val shape = 0.55f + 0.25f * sin(u * tau / period + seed) +
            0.14f * sin(u * tau / (period * 0.37f) + seed * 1.7f) +
            0.06f * sin(u * tau / (period * 0.13f) + seed * 2.3f)
        p.lineTo(x, cam.horizon - amp * shape)
    }
    p.lineTo(cam.w, cam.horizon + 2f)
    p.close()
    drawPath(p, color)
}

/**
 * 먼 구간부터 가까운 구간 순으로 땅 띠 → 노면 → 차선 리본 → 실선·점선 → 반사봉을 그린다.
 * 가까운 땅 띠가 나중에 덮으므로 고개 너머 길은 자연스럽게 가려진다.
 */
private fun DrawScope.drawRoad(cam: SceneCamera, g: RoadGeometry, playing: Boolean, time: Float) {
    val p = Path()
    val glow = if (playing) 0.30f + 0.05f * sin(time * 2f * PI.toFloat() / 2.4f) else 0.22f

    fun edgeX(i: Int, o: Float) = cam.sx(g.forward[i] - o * sin(g.angle[i]), g.lateral[i] + o * cos(g.angle[i]))
    fun edgeY(i: Int, o: Float, z: Float = g.height[i]) = cam.sy(g.forward[i] - o * sin(g.angle[i]), z)

    /**
     * 구간 i(가까운 점)→i+1(먼 점)의 가로 o1..o2 띠. 먼 쪽 끝을 살짝 위로 늘려, 나중에 그리는 가까운 땅 띠의
     * 윗선이 앞 구간 노면 위에 이음선으로 남지 않게 덮는다.
     */
    fun quad(i: Int, o1: Float, o2: Float) {
        p.reset()
        p.moveTo(edgeX(i, o1), edgeY(i, o1))
        p.lineTo(edgeX(i + 1, o1), edgeY(i + 1, o1) - 0.75f)
        p.lineTo(edgeX(i + 1, o2), edgeY(i + 1, o2) - 0.75f)
        p.lineTo(edgeX(i, o2), edgeY(i, o2))
        p.close()
    }

    for (i in g.count - 2 downTo 0) {
        val dNear = cam.depth(g.forward[i])
        val dFar = cam.depth(g.forward[i + 1])
        if (dNear < SceneCamera.NEAR || dFar < SceneCamera.NEAR) continue
        val yNear = cam.sy(g.forward[i], g.height[i])
        val yFar = cam.sy(g.forward[i + 1], g.height[i + 1])
        if (yFar >= yNear) continue // 고개 너머로 내려가는 면: 앞쪽 땅에 가려지므로 그리지 않는다
        val fog = fogAt(dFar)
        val world = g.firstIndex + i

        // 땅: 가로 전체 띠. 멀수록 안개색으로 녹아든다.
        drawRect(lerp(Ground, Haze, fog), Offset(0f, yFar), Size(cam.w, yNear - yFar + 1f))

        quad(i, -ROAD_HALF, ROAD_HALF)
        drawPath(p, lerp(Asphalt, Haze, fog))

        // 가운데 차선 경로 리본: 차 앞에서 가장 진하고 멀어질수록 사라진다
        quad(i, -RIBBON_HALF, RIBBON_HALF)
        drawPath(p, ActiveBlue.copy(alpha = glow * (1f - fog)))

        val lineColor = Color.White.copy(alpha = 1f - fog * 0.75f)
        for (side in intArrayOf(-1, 1)) {
            quad(i, side * EDGE_LINE - LINE_HALF, side * EDGE_LINE + LINE_HALF)
            drawPath(p, lineColor)
        }
        // 차선 점선: 8m 칠하고 12m 비운다(노면에 고정돼 달린 만큼만 흐른다)
        if (floorMod(world, 5) < 2) {
            for (side in intArrayOf(-1, 1)) {
                quad(i, side * LANE_LINE - LINE_HALF, side * LANE_LINE + LINE_HALF)
                drawPath(p, lineColor)
            }
        }
        // 갓길 반사봉(40m 간격): 굽이와 오르내림을 눈으로 따라가게 해 준다
        if (floorMod(world, 10) == 0) {
            for (side in intArrayOf(-1, 1)) {
                val o = side * (ROAD_HALF + 0.9f)
                val d = max(cam.depth(g.forward[i] - o * sin(g.angle[i])), SceneCamera.NEAR)
                val x = edgeX(i, o)
                val yBase = edgeY(i, o)
                val yTop = edgeY(i, o, g.height[i] + 1.0f)
                val pw = cam.focal * 0.13f / d
                drawRect(Color.White.copy(alpha = 0.95f - fog * 0.8f), Offset(x - pw * 0.5f, yTop), Size(pw, yBase - yTop))
                drawRect(Reflector.copy(alpha = 0.9f - fog * 0.85f), Offset(x - pw * 0.5f, yTop + (yBase - yTop) * 0.08f), Size(pw, (yBase - yTop) * 0.16f))
            }
        }
    }
}

private fun DrawScope.drawCar(cam: SceneCamera, g: RoadGeometry, time: Float, playing: Boolean) {
    val bob = if (playing) sin(time * 2f * PI.toFloat() * 1.1f) * cam.h * 0.0010f else 0f
    // 차를 카메라에서 조금 떨어진 곳(약 10m 앞)에 두어 작고 단정하게 보이게 한다. 반폭·접지 높이는 같은 카메라로 구한다.
    val halfW = cam.focal * 0.95f / cam.depth(1.2f)
    drawRearCar(cam.w * 0.5f, cam.sy(1.4f, 0f), halfW, tilt = g.nearCurve * 2.5f, bob = bob)
}

/**
 * 뒤 조금 위(약 26도)에서 본 세단(일반형, 특정 차종 아님). [halfW]는 뒤 범퍼 반폭, [groundY]는 뒷바퀴가 노면에 닿는 높이.
 * 범퍼·후미등·트렁크·뒷유리·지붕의 높이와 폭은 같은 시점에서 실제 세단 치수(폭 1.9m, 높이 1.45m)를 투영한 비율이다.
 * 후미등 같은 색 장식 없이 펄 화이트 면의 음영·광택과 뒷유리, 가는 캐릭터 라인으로만 표현하고, [tilt](도)만큼 진행 방향으로 기운다.
 */
internal fun DrawScope.drawRearCar(cx: Float, groundY: Float, halfW: Float, tilt: Float = 0f, bob: Float = 0f) {
    val yGround = groundY
    fun y(r: Float) = groundY - halfW * r + bob
    val yBumper = y(0.190f)
    val yLight = y(0.739f)
    val yDeck = y(1.044f)
    val yGlassLow = y(1.247f)
    val yGlassTop = y(1.907f)
    val yRoof = y(2.363f)
    val wBody = halfW
    val wDeck = halfW * 0.909f
    val wCabinLow = halfW * 0.769f
    val wCabinTop = halfW * 0.529f
    val wRoof = halfW * 0.392f

    rotate(tilt, pivot = Offset(cx, yGround)) {
        // 바닥 그림자: 차 밑으로 넓게 번지는 그늘
        val sw = halfW * 1.06f
        val sh = halfW * 1.386f
        drawOval(
            Brush.radialGradient(
                0f to Color(0xFF0B1F3A).copy(alpha = 0.24f),
                0.6f to Color(0xFF0B1F3A).copy(alpha = 0.08f),
                1f to Color.Transparent,
                center = Offset(cx, yGround - sh * 0.45f),
                radius = sw,
            ),
            topLeft = Offset(cx - sw, yGround - sh * 1.1f),
            size = Size(sw * 2f, sh * 1.35f),
        )
        // 바퀴: 범퍼 아래로 살짝 보이는 타이어
        val tireW = halfW * 0.258f
        val tireTop = yLight + (yBumper - yLight) * 0.55f
        for (side in intArrayOf(-1, 1)) {
            drawRoundRect(
                Color(0xFF1C232D),
                topLeft = Offset(cx + side * wBody * 0.78f - tireW * 0.5f, tireTop),
                size = Size(tireW, yGround - tireTop),
                cornerRadius = CornerRadius(tireW * 0.28f),
            )
        }
        // 사이드미러
        val ym = y(1.926f)
        val mirrorOut = halfW * 0.775f
        val mirrorIn = halfW * 0.559f
        for (side in intArrayOf(-1, 1)) {
            drawRoundRect(
                Color(0xFFE4E9EF),
                topLeft = Offset(if (side < 0) cx - mirrorOut else cx + mirrorIn, ym - (mirrorOut - mirrorIn) * 0.42f),
                size = Size(mirrorOut - mirrorIn, (mirrorOut - mirrorIn) * 0.42f),
                cornerRadius = CornerRadius((mirrorOut - mirrorIn) * 0.2f),
            )
        }

        // 차체 실루엣(왼쪽 점을 오른쪽으로 대칭): 범퍼 → 펜더 → 트렁크 어깨 → C필러 → 지붕
        val left = listOf(
            Offset(-wBody * 0.93f, yBumper),
            Offset(-wBody, yBumper - (yBumper - yLight) * 0.25f),
            Offset(-wBody * 0.99f, yLight),
            Offset(-wDeck * 0.97f, yDeck),
            Offset(-wCabinLow, yGlassLow),
            Offset(-wCabinTop, yGlassTop),
            Offset(-wRoof * 0.92f, yRoof + (yGlassTop - yRoof) * 0.25f),
            Offset(-wRoof * 0.62f, yRoof),
        )
        val body = smoothSymmetric(cx, left)
        // 펄 화이트: 위는 하늘빛을 받아 밝고 아래로 갈수록 은은한 회색. 양옆은 곡면이 꺾이며 깊게 가라앉는다.
        drawPath(body, Brush.verticalGradient(listOf(Color.White, Color(0xFFF3F5F8), Color(0xFFDDE3EA), Color(0xFFC6CFD9)), startY = yRoof, endY = yBumper))
        drawPath(
            body,
            Brush.horizontalGradient(
                0f to Color(0xFF4F607A).copy(alpha = 0.40f),
                0.18f to Color.Transparent,
                0.82f to Color.Transparent,
                1f to Color(0xFF4F607A).copy(alpha = 0.44f),
                startX = cx - wBody,
                endX = cx + wBody,
            ),
        )
        // 트렁크 위 넓게 번지는 광택
        drawOval(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.75f), Color.Transparent), center = Offset(cx - wBody * 0.25f, yDeck + (yLight - yDeck) * 0.2f), radius = wBody * 0.7f),
            topLeft = Offset(cx - wBody * 0.95f, yDeck - (yLight - yDeck) * 0.2f),
            size = Size(wBody * 1.4f, (yLight - yDeck) * 0.9f),
        )
        drawPath(body, Color(0xFFB4BFCC).copy(alpha = 0.8f), style = Stroke(width = wBody * 0.006f))

        // 지붕 윗면: 하늘빛을 받아 조금 더 밝다
        val roof = smoothSymmetric(
            cx,
            listOf(Offset(-wCabinTop * 0.93f, yGlassTop), Offset(-wRoof * 0.88f, yRoof + (yGlassTop - yRoof) * 0.2f), Offset(-wRoof * 0.58f, yRoof + 1f)),
        )
        drawPath(roof, Brush.verticalGradient(listOf(Color.White, Color(0xFFEEF2F6)), startY = yRoof, endY = yGlassTop))

        // 뒷유리: 깊은 남색 유리에 하늘이 비스듬히 비친다
        val glass = smoothSymmetric(
            cx,
            listOf(
                Offset(-wCabinLow * 0.84f, yGlassLow - (yGlassLow - yGlassTop) * 0.06f),
                Offset(-wCabinTop * 0.86f, yGlassTop + (yGlassLow - yGlassTop) * 0.12f),
                Offset(-wCabinTop * 0.6f, yGlassTop + (yGlassLow - yGlassTop) * 0.08f),
            ),
        )
        drawPath(glass, Brush.verticalGradient(listOf(Color(0xFF34445A), Color(0xFF151D28), Color(0xFF0C121A)), startY = yGlassTop, endY = yGlassLow))
        drawPath(
            glass,
            Brush.linearGradient(
                0f to Color.Transparent,
                0.35f to Color.White.copy(alpha = 0.16f),
                0.5f to Color.White.copy(alpha = 0.04f),
                1f to Color.Transparent,
                start = Offset(cx - wCabinLow, yGlassTop),
                end = Offset(cx + wCabinLow * 0.4f, yGlassLow),
            ),
        )

        // 트렁크 끝 립: 가는 빛줄과 그 아래 얇은 그늘
        drawLine(Color.White, Offset(cx - wDeck * 0.88f, yDeck + 1f), Offset(cx + wDeck * 0.88f, yDeck + 1f), strokeWidth = wBody * 0.010f, cap = StrokeCap.Round)
        drawLine(Color(0xFFB9C3CF).copy(alpha = 0.7f), Offset(cx - wDeck * 0.86f, yDeck + wBody * 0.03f), Offset(cx + wDeck * 0.86f, yDeck + wBody * 0.03f), strokeWidth = wBody * 0.008f, cap = StrokeCap.Round)

        // 차체 아래 캐릭터 라인(후미등 대신 면이 꺾이는 선 하나로 정리)
        val crease = yLight + (yBumper - yLight) * 0.12f
        drawLine(Color(0xFFAEB9C6).copy(alpha = 0.75f), Offset(cx - wBody * 0.90f, crease), Offset(cx + wBody * 0.90f, crease), strokeWidth = wBody * 0.009f, cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.85f), Offset(cx - wBody * 0.88f, crease + wBody * 0.014f), Offset(cx + wBody * 0.88f, crease + wBody * 0.014f), strokeWidth = wBody * 0.007f, cap = StrokeCap.Round)

        // 아래 범퍼(아주 옅은 짙은 띠)
        val bumperTop = yBumper - (yBumper - yLight) * 0.26f
        drawRoundRect(
            Color(0xFF3E4857).copy(alpha = 0.28f),
            Offset(cx - wBody * 0.80f, bumperTop),
            Size(wBody * 1.60f, (yBumper - bumperTop) * 0.86f),
            CornerRadius((yBumper - bumperTop) * 0.4f),
        )
    }
}

/** 왼쪽 점 목록(아래→위)을 오른쪽으로 대칭해 모서리를 둥글린 닫힌 경로를 만든다. x는 중심 기준 오프셋. */
private fun smoothSymmetric(cx: Float, left: List<Offset>): Path {
    val pts = left.map { Offset(cx + it.x, it.y) } + left.reversed().map { Offset(cx - it.x, it.y) }
    val p = Path()
    val n = pts.size
    fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)
    val start = mid(pts[n - 1], pts[0])
    p.moveTo(start.x, start.y)
    for (i in 0 until n) {
        val c = pts[i]
        val e = mid(c, pts[(i + 1) % n])
        p.quadraticBezierTo(c.x, c.y, e.x, e.y)
    }
    p.close()
    return p
}

/**
 * 천천히 다가와 스쳐 지나가는 빛망울. 길 끝에서 생겨 화면 가장자리로 퍼지며 사라진다.
 * 위치는 시간과 번호로만 정해져 매번 같은 흐름이고, 깜빡임 없이 서서히 나타나고 사라진다.
 */
private fun DrawScope.drawLightMotes(w: Float, h: Float, time: Float, curve: Float, playing: Boolean) {
    for (i in 0 until 10) {
        val period = (9f + (i * 37 % 7)) * (if (playing) 1f else 1.6f)
        val u = (time / period + i * 0.618034f) % 1f
        val side = if (i % 2 == 0) -1f else 1f
        val spread = 0.25f + ((i * 53) % 10) / 10f * 0.55f
        val x = w * 0.5f + curve * w * 0.15f * (1f - u) + side * spread * w * 0.55f * u * u
        val y = h * 0.30f + h * 0.7f * u.pow(1.5f)
        val r = (1.5f + 6f * u).dp.toPx() * 2.2f
        val a = sin(PI.toFloat() * u).pow(1.5f) * (if (playing) 0.40f else 0.22f)
        drawCircle(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = a),
                0.4f to ActiveBlue.copy(alpha = a * 0.22f),
                1f to Color.Transparent,
                center = Offset(x, y),
                radius = r,
            ),
            radius = r,
            center = Offset(x, y),
        )
    }
}
