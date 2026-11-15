plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // 에뮬레이터 없이 화면을 그려 확인하는 스크린샷 테스트(테스트 전용, 앱에는 포함되지 않음)
    alias(libs.plugins.paparazzi)
}

// release 서명 정보는 저장소 밖에 두고 프로젝트 루트의 keystore.properties(.gitignore 대상)로만 읽는다.
// 파일이 없으면 서명하지 않은 release APK가 만들어진다. 형식은 keystore.properties.example 참고.
val signingProps = java.util.Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.studio1950.melroad"
    // Media3 1.5.x가 compileSdk 35 이상을 요구한다. 실행 동작은 Pleos Connect 이미지(API 34)에 맞춰 targetSdk 34 유지.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.studio1950.melroad"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // AAOS Car API(CarPropertyManager, CarUxRestrictionsManager)는 SDK의 optional android.car 라이브러리로 컴파일한다.
    useLibrary("android.car")

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (signingProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core-music"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    // Media3 공개 API(ListenableFuture, ImmutableList)에 쓰이는 Guava를 명시한다.
    implementation(libs.guava)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // Pleos Connect SDK: 실제 클래스·메서드를 확인한 뒤 어댑터를 연결한다(PleosApiProbeTest 참고).
    implementation(libs.pleos.fusedlocation)
    implementation(libs.pleos.vehicle)

    testImplementation(libs.junit)
}

// Paparazzi(LayoutLib)는 Guava의 -jre 변형을 쓰는데, 앱은 -android 변형을 쓴다. 테스트 경로에서만 -jre를 고르게 한다.
// 출처: Paparazzi 변경 기록(https://cashapp.github.io/paparazzi/changelog)의 안내.
plugins.withId("app.cash.paparazzi") {
    afterEvaluate {
        dependencies.constraints {
            add("testImplementation", "com.google.guava:guava") {
                attributes {
                    attribute(
                        TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                        objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM),
                    )
                }
                because("LayoutLib and sdk-common depend on Guava's -jre published variant.")
            }
        }
    }
}
