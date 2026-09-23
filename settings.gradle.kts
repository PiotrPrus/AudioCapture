rootProject.name = "AudioCapture"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":audio-capture")

include(":samples:shared")
include(":samples:androidApp")
