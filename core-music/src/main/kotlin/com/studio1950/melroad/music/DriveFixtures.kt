package com.studio1950.melroad.music

/**
 * 시뮬레이션(가상) 주행 데이터.
 *
 * 모두 장면(구간) 규칙으로 만든 합성 데이터이며 실제 도로·실시간 센서 기록이 아니다.
 * 실제 여정처럼 15~30분 길이로 만들어 긴 주행에서 음악이 어떻게 변하는지 확인할 수 있게 했다.
 * "제주 해안도로"는 해안도로의 완만한 굽이·정차·언덕 흐름을 본뜬 가상 경로로, 실제 도로 형상과 일치하지 않는다.
 * 정제 파이프라인 검증을 위해 일부 구간에 위치 신호 끊김과 GPS 튐을 의도적으로 넣었다.
 */
data class DriveFixture(
    val id: String,
    val title: String,
    val description: String,
    val seed: Long,
    val samples: List<DriveSample>,
) {
    val durationMs: Long get() = samples.last().timestampMs
}

object DriveFixtures {

    /** 약 30분. 해안 순항·S자 굽이·마을 정차·해안 언덕·전망 지점 휴식이 섞인 전체 여정(가상). */
    val COAST: DriveFixture by lazy {
        build(
            id = "coast",
            title = "제주 해안도로",
            description = "바닷가를 따라 굽이치는 약 30분 여정 · 시뮬레이션",
            seed = 0x4A454A55L, // "JEJU"
            startLat = 33.4640, startLon = 126.3100, startAltM = 12.0, startBearing = 245.0,
            segments = procedural(
                salt = 0x434F415354L,
                targetSeconds = 30 * 60,
                opening = listOf(Segment(15, 30f), Segment(25, 45f, turnDegPerS = -1.2f)),
                scenes = listOf(
                    { r -> listOf(Segment(r.nextInt(40, 90), 52f + r.nextInt(0, 10), turnDegPerS = (r.nextDouble() * 2 - 1).toFloat() * 1.2f)) },
                    { _ -> listOf(Segment(12, 45f, turnDegPerS = 5f), Segment(12, 45f, turnDegPerS = -5f)) },
                    { r -> listOf(Segment(15, 20f), Segment(r.nextInt(25, 40), 0f), Segment(15, 35f)) },
                    { _ -> listOf(Segment(35, 50f, turnDegPerS = 1.0f, climbMps = 0.9f), Segment(30, 45f, turnDegPerS = -1.5f, climbMps = -0.9f)) },
                    { _ -> listOf(Segment(20, 40f, turnDegPerS = -3f), Segment(20, 50f, turnDegPerS = 2.5f)) },
                    { r -> listOf(Segment(15, 25f), Segment(r.nextInt(45, 75), 0f), Segment(15, 30f)) },
                ),
                // 정제 검증용: 위치 신호 끊김 구간(약 10분 지점)
                dropoutAtSecond = 600,
            ),
            gpsSpikeAtSecond = 95,
        )
    }

    /** 약 20분. 지속 오르막과 급한 굽이, 정상 부근 완만한 구간, 내리막(가상). */
    val HILL: DriveFixture by lazy {
        build(
            id = "hill",
            title = "언덕길",
            description = "오르막과 굽은 길이 이어지는 약 20분 산길 · 시뮬레이션",
            seed = 0x48494C4CL, // "HILL"
            startLat = 33.3600, startLon = 126.4600, startAltM = 300.0, startBearing = 20.0,
            segments = procedural(
                salt = 0x48494C4C53L,
                targetSeconds = 20 * 60,
                opening = listOf(Segment(15, 30f), Segment(30, 40f, turnDegPerS = 1f, climbMps = 1.4f)),
                scenes = listOf(
                    { r -> listOf(Segment(r.nextInt(25, 40), 38f, turnDegPerS = -1f, climbMps = 1.5f), Segment(10, 25f, turnDegPerS = 12f, climbMps = 0.8f)) },
                    { r -> listOf(Segment(r.nextInt(25, 40), 40f, turnDegPerS = 1f, climbMps = 1.2f), Segment(10, 25f, turnDegPerS = -12f, climbMps = 0.8f)) },
                    { _ -> listOf(Segment(30, 42f, turnDegPerS = 0.5f)) },
                    { r -> listOf(Segment(r.nextInt(25, 40), 45f, turnDegPerS = 2f, climbMps = -1.4f), Segment(10, 25f, turnDegPerS = 12f, climbMps = -0.8f)) },
                ),
            ),
        )
    }

    /** 약 15분. 신호 대기와 출발, 좌·우회전이 반복되는 시내(가상). */
    val CITY: DriveFixture by lazy {
        build(
            id = "city",
            title = "도심",
            description = "신호 대기와 출발이 반복되는 약 15분 시내 · 시뮬레이션",
            seed = 0x43495459L, // "CITY"
            startLat = 33.4990, startLon = 126.5310, startAltM = 30.0, startBearing = 90.0,
            segments = procedural(
                salt = 0x4349545953L,
                targetSeconds = 15 * 60,
                opening = listOf(Segment(15, 30f)),
                scenes = listOf(
                    { r -> listOf(Segment(r.nextInt(15, 30), 40f), Segment(10, 15f), Segment(r.nextInt(25, 40), 0f)) },
                    { _ -> listOf(Segment(15, 30f), Segment(8, 12f, turnDegPerS = 11f), Segment(15, 35f)) },
                    { _ -> listOf(Segment(15, 30f), Segment(8, 12f, turnDegPerS = -11f), Segment(15, 35f)) },
                    { r -> listOf(Segment(r.nextInt(20, 35), 45f)) },
                ),
            ),
        )
    }

    val all: List<DriveFixture> get() = listOf(COAST, HILL, CITY)

    fun byId(id: String): DriveFixture? = all.firstOrNull { it.id == id }

    private data class Segment(
        val seconds: Int,
        val targetKmh: Float,
        val turnDegPerS: Float = 0f,
        val climbMps: Float = 0f,
        val gpsDropout: Boolean = false,
    )

    /** 장면 목록에서 seed 기반으로 골라 목표 길이까지 이어 붙인다. 마지막은 항상 정차(도착). */
    private fun procedural(
        salt: Long,
        targetSeconds: Int,
        opening: List<Segment>,
        scenes: List<(SeededRandom) -> List<Segment>>,
        dropoutAtSecond: Int? = null,
    ): List<Segment> {
        val rng = SeededRandom(salt)
        val out = ArrayList(opening)
        var total = opening.sumOf { it.seconds }
        var dropoutDone = dropoutAtSecond == null
        var previous = -1
        while (total < targetSeconds - 40) {
            var pick = rng.nextInt(scenes.size)
            if (pick == previous) pick = (pick + 1) % scenes.size // 같은 장면 연속 방지
            previous = pick
            for (segment in scenes[pick](rng)) {
                out.add(segment)
                total += segment.seconds
            }
            if (!dropoutDone && total >= dropoutAtSecond!!) {
                out.add(Segment(15, 50f, gpsDropout = true))
                total += 15
                dropoutDone = true
            }
        }
        out.add(Segment(15, 15f))
        out.add(Segment(20, 0f)) // 도착
        return out
    }

    /** 1초 간격으로 구간 규칙을 적분해 샘플을 만든다. 잡음도 seed 기반이라 매번 같다. */
    private fun build(
        id: String,
        title: String,
        description: String,
        seed: Long,
        startLat: Double,
        startLon: Double,
        startAltM: Double,
        startBearing: Double,
        segments: List<Segment>,
        gpsSpikeAtSecond: Int? = null,
    ): DriveFixture {
        val rng = SeededRandom(seed * 31 + 17)
        val samples = ArrayList<DriveSample>()
        var lat = startLat
        var lon = startLon
        var alt = startAltM
        var bearing = startBearing
        var speed = 0.0 // m/s
        var second = 0

        fun noise(amplitude: Double) = (rng.nextDouble() * 2 - 1) * amplitude

        fun emit(dropout: Boolean) {
            val moving = speed > 0.3
            val reportedSpeed = if (moving) (speed + noise(0.25)).coerceAtLeast(0.0) else 0.0
            var sLat: Double? = lat + noise(1.5) / 111_000.0
            var sLon: Double? = lon + noise(1.5) / 93_000.0
            if (gpsSpikeAtSecond != null && second == gpsSpikeAtSecond) {
                sLat = lat + 0.01 // 약 1.1km 순간 이동(GPS 튐)
            }
            var sAlt: Double? = alt + noise(0.6)
            var sBearing: Float? = if (speed > 1.0) (((bearing + noise(1.5)) % 360.0 + 360.0) % 360.0).toFloat() else null
            if (dropout) {
                sLat = null
                sLon = null
                sAlt = null
                sBearing = null
            }
            samples.add(
                DriveSample(
                    timestampMs = second * 1000L,
                    speedMps = reportedSpeed.toFloat(),
                    latitude = sLat,
                    longitude = sLon,
                    altitudeM = sAlt,
                    bearingDeg = sBearing,
                ),
            )
        }

        emit(dropout = false)
        for (segment in segments) {
            repeat(segment.seconds) {
                val target = segment.targetKmh / 3.6
                val accel = (target - speed).coerceIn(-2.5, 1.8)
                speed = (speed + accel).coerceAtLeast(0.0)
                if (speed > 0.5) {
                    bearing = ((bearing + segment.turnDegPerS) % 360.0 + 360.0) % 360.0
                    alt += segment.climbMps
                    val moved = GeoMath.offset(lat, lon, bearing, speed)
                    lat = moved.first
                    lon = moved.second
                }
                second++
                emit(segment.gpsDropout)
            }
        }
        return DriveFixture(id, title, description, seed, samples)
    }
}
