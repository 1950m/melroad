package com.studio1950.melroad

import android.app.Application
import androidx.room.Room
import com.studio1950.melroad.audio.AudioEngine
import com.studio1950.melroad.audio.AudioFocusController
import com.studio1950.melroad.data.MelroadDatabase
import com.studio1950.melroad.data.TripRepository
import com.studio1950.melroad.integration.DrivingRestrictionMonitor
import com.studio1950.melroad.integration.RealVehicleSignalSource
import com.studio1950.melroad.integration.car.CarConnector
import com.studio1950.melroad.session.TripSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MelroadApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 수동 의존성 구성. 앱 프로세스당 하나의 재생 엔진·세션을 공유한다(화면·미디어 서비스 공용). */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val database: MelroadDatabase =
        Room.databaseBuilder(app, MelroadDatabase::class.java, "melroad.db").build()

    val repository = TripRepository(database, app.cacheDir)
    val carConnector = CarConnector(app)
    val drivingMonitor = DrivingRestrictionMonitor(carConnector)
    private val audioEngine = AudioEngine().apply {
        // 오케스트라 샘플(약 20MB)은 백그라운드에서 올린다. 준비되기 전에는 합성 음색으로 연주한다.
        loadOrchestra { name -> runCatching { app.assets.open("orchestra/$name") }.getOrNull() }
    }
    private val focus = AudioFocusController(app)
    private val realSource = RealVehicleSignalSource(app, carConnector)

    val sessionController = TripSessionController(app, audioEngine, repository, focus, realSource, scope)
}
