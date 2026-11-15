plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pleose.car"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // AAOS 표준 차량 프로퍼티 (GEAR_SELECTION, PERF_VEHICLE_SPEED) — Pleos Vehicle SDK의 Gear API는 deprecated
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.pleose.car"
        // Pleos Connect 요구사항: minSdk API 28 이상
        minSdk = 28
        // Pleos Connect Emulator system image: API 34
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 단위 테스트에서 android.util.Log 등은 기본값을 돌려주게 한다
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Pleos Connect SDK: 차량 상태(Vehicle), 내비 경로·경유지·장소 검색(NaviHelper), 음성 안내(TextToSpeech), 음성 인식(SpeechToText)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json.test) // 단위 테스트에서 실제 org.json (Gleo 파라미터)
}
