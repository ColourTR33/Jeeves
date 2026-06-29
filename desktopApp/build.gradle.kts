import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
                implementation("io.ktor:ktor-client-core:2.3.7")
                implementation("io.ktor:ktor-client-cio:2.3.7")
                // Global hotkey support
                implementation("com.github.kwhat:jnativehook:2.2.2")
                // SQLite database
                implementation("org.xerial:sqlite-jdbc:3.45.1.0")
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.kotest:kotest-property:5.8.0")
                implementation("io.kotest:kotest-assertions-core:5.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.jeeves.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Jeeves"
            packageVersion = "1.2.0"
            description = "Meeting recorder, transcriber and summariser"
            vendor = "Jeeves"

            // Include all JVM modules needed by the app and its dependencies
            includeAllModules = true

            macOS {
                bundleID = "com.jeeves.desktop"
                entitlementsFile.set(project.file("src/desktopMain/resources/entitlements.plist"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Jeeves needs microphone access to record meetings for transcription.</string>
                        <key>NSAppleEventsUsageDescription</key>
                        <string>Jeeves needs access to Apple Reminders to export action items.</string>
                    """.trimIndent()
                }
            }

            windows {
                menuGroup = "Jeeves"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
    }
}
