// Build toolchain that runs on Java 17–25:
//   Gradle 9.3.1 (wrapper) + Android Gradle Plugin 9.1 + Kotlin 2.4
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
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    id("com.android.application") version "9.5.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
