pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Playground Maven 저장소 (Pleos Connect SDK)
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}

rootProject.name = "pleose"

include(":shared")       // 공용 도메인: 부탁 모델·상태 흐름·해석·장소 추천·순서 최적화·음성 스크립트·API
include(":server")       // 가족 중계 서버 (요청자 앱 ↔ 차량 앱)
include(":pleos-app")    // 수행자(운전자)용 Pleos Connect 차량 앱
include(":android-app")  // 요청자(가족)용 Android 스마트폰 앱
