pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "on-device-scanner"

include(
    ":app",
    ":core:camera",
    ":core:ml",
    ":core:ocr",
    ":core:parse",
    ":core:bench",
    ":core:data",
    ":core:model",
    ":feature:scan",
    ":feature:history",
    ":feature:benchmark",
)
