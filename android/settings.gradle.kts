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
        // Local AAR drop-in for the QNN TFLite Delegate (downloaded separately
        // from Qualcomm Developer Network — not on Maven).
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Pramana"
include(":app")
