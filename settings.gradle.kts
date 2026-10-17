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
        google()        // androidx.xr.*, androidx.camera.*, compose
        mavenCentral()  // org.pytorch:executorch-android, kotlinx
    }
}

rootProject.name = "vysion-xr"
include(":app")
