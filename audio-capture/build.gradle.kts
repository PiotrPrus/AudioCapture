import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

kotlin {
    explicitApi()

    android {
        namespace = "dev.piotrprus.audiocapture"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.startup)
            implementation(libs.androidx.annotation)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("io.github.piotrprus", "audio-capture", "0.1.0")

    pom {
        name.set("AudioCapture")
        description.set("Kotlin Multiplatform microphone capture for Android and iOS: a Flow of raw PCM chunks, AAC or WAV file recording, or both at once, with live levels, pause/resume, input devices and interruption handling.")
        inceptionYear.set("2026")
        url.set("https://github.com/PiotrPrus/AudioCapture/")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("piotrprus")
                name.set("Piotr Prus")
                url.set("https://github.com/PiotrPrus/")
            }
        }

        scm {
            url.set("https://github.com/PiotrPrus/AudioCapture/")
            connection.set("scm:git:git://github.com/PiotrPrus/AudioCapture.git")
            developerConnection.set("scm:git:ssh://git@github.com/PiotrPrus/AudioCapture.git")
        }
    }
}

dokka {
    moduleName.set("AudioCapture")

    dokkaSourceSets.configureEach {
        // Link generated pages back to the source on GitHub.
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/PiotrPrus/AudioCapture/tree/main/audio-capture/src")
            remoteLineSuffix.set("#L")
        }
        // Surface the README on the module's landing page.
        includes.from("Module.md")
    }

    pluginsConfiguration.html {
        footerMessage.set("© 2026 Piotr Prus · AudioCapture — Apache 2.0")
    }
}
