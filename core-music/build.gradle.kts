import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 순수 Kotlin 작곡 엔진. Android Context·Pleos SDK·차량 API 의존성을 넣지 않는다.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
