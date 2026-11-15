package com.studio1950.melroad.music

/**
 * 짧은 구간용 지리 계산. 플랫폼 간 결과 차이를 줄이려고 StrictMath를 쓴다.
 * (Math의 삼각함수는 하드웨어 인트린직에 따라 마지막 비트가 달라질 수 있다.)
 */
internal object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val s1 = StrictMath.sin(dp / 2)
        val s2 = StrictMath.sin(dl / 2)
        val a = s1 * s1 + StrictMath.cos(p1) * StrictMath.cos(p2) * s2 * s2
        return 2 * EARTH_RADIUS_M * StrictMath.atan2(StrictMath.sqrt(a), StrictMath.sqrt(1 - a))
    }

    /** 0..360, 북쪽 0, 시계 방향 증가 */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = StrictMath.sin(dl) * StrictMath.cos(p2)
        val x = StrictMath.cos(p1) * StrictMath.sin(p2) - StrictMath.sin(p1) * StrictMath.cos(p2) * StrictMath.cos(dl)
        val deg = Math.toDegrees(StrictMath.atan2(y, x))
        return (((deg % 360.0) + 360.0) % 360.0).toFloat()
    }

    /** 두 방향 차이를 -180..180으로 정규화 */
    fun normalizeDelta(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /** 시뮬레이션 경로 생성용: 현재 위치에서 bearing 방향으로 distM 이동한 좌표 */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val br = Math.toRadians(bearingDeg)
        val dLat = distM * StrictMath.cos(br) / EARTH_RADIUS_M
        val dLon = distM * StrictMath.sin(br) / (EARTH_RADIUS_M * StrictMath.cos(Math.toRadians(lat)))
        return Pair(lat + Math.toDegrees(dLat), lon + Math.toDegrees(dLon))
    }
}
