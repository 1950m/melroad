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
        // Pleos Connect SDK(FusedLocation, Vehicle). 공식 문서의 project-setup 안내를 따른다.
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content { includeGroup("ai.pleos.playground") }
        }
    }
}

rootProject.name = "Melroad"
include(":app", ":core-music")
