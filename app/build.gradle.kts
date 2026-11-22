plugins {
    id("com.android.application")
    // Kotlin is built into AGP 9; only the Compose compiler plugin is applied.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.antenna"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.antenna"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Keep minify off: NewPipeExtractor uses Rhino + reflection and needs
            // extra ProGuard rules if you turn this on (see its README).
            isMinifyEnabled = false
            // Signed with the debug key so you can sideload a release build easily.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // The app's bytecode targets Java 17 (what Android supports). This is separate
    // from the JDK that runs the build, which can be anything from 17 to 25.
    // Kotlin's jvmTarget follows targetCompatibility automatically.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    // YouTube stream/search extraction (same library NewPipe uses). Bump this
    // when YouTube breaks things: https://github.com/TeamNewPipe/NewPipeExtractor/releases
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Player
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // UI
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
