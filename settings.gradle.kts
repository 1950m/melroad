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
        // Pleos Connect SDK 저장소는 FusedLocation 어댑터(P6)를 실제로 연결할 때 추가한다.
        // 공식 문서: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/project-setup
    }
}

rootProject.name = "Melroad"
include(":app", ":core-music")
