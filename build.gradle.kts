// Build toolchain that runs on Java 17–25: the Gradle wrapper (its version is in
// gradle/wrapper/gradle-wrapper.properties), the Android Gradle Plugin and Kotlin
// (their versions are below, so this comment needn't change when they do).
//
// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the old
// org.jetbrains.kotlin.android plugin is gone. The classpath line below only
// pins which Kotlin version AGP uses; keep it equal to the Compose plugin version.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
