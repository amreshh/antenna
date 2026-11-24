plugins {
    id("com.android.application")
    // Kotlin is built into AGP 9; only the Compose compiler plugin is applied.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // The code's package (and R's). The app's id, applicationId below, stays com.example.antenna:
    // a new one would make Android treat it as another app, which doesn't install over the one
    // people have (and their history and subscriptions stay with that one).
    namespace = "com.antenna"
    // Android 17 QPR2 (API 37.2): Compose 1.13 needs at least 37.1 to compile against.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.example.antenna"
        minSdk = 26
        // Android 16: edge-to-edge (done in MainActivity), predictive back (BackHandler
        // supports it), and on large screens no orientation lock (the app only locks
        // landscape for fullscreen on phones). Not 37 yet: targeting Android 17 made every
        // video fail on the Android 17 emulator (ERROR_CODE_DECODING_FAILED: the decoder
        // failed when ExoPlayer switched its output surface), while 36 plays fine there.
        // Try 37 again on a real Android 17 phone, then drop the lint exception below.
        targetSdk = 36
        // The first release's version. After that, CI passes the release's version
        // (-PantennaVersion=1.2.3, from the git tag); see .github/workflows/ci.yaml.
        versionName = (findProperty("antennaVersion") as String?) ?: "0.1.0"
        versionCode = versionCodeOf(versionName!!)
    }

    lint {
        // "Not targeting the latest Android": on purpose, see targetSdk above.
        disable += "OldTargetApi"
    }

    // CI signs releases with the release key (repository secrets, see the README).
    // Every release has to be signed with the same key, or Android refuses to
    // install it as an update. Without the key (local builds) the debug key is used.
    signingConfigs {
        System.getenv("ANTENNA_KEYSTORE")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ANTENNA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANTENNA_KEY_ALIAS")
                keyPassword = System.getenv("ANTENNA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Code coverage of the unit tests (JaCoCo, built into the Android Gradle plugin):
            // ./gradlew createDebugUnitTestCoverageReport writes it to
            // app/build/reports/coverage/test/debug/. CI puts it in the pull request's
            // comment, with how it changed from the base branch (.github/scripts/coverage-summary.py).
            enableUnitTestCoverage = true
        }
        release {
            // R8 removes the unused parts of the libraries (most of Compose, Material and
            // Media3), which makes the APK a fraction of the size and Compose noticeably
            // faster. proguard-rules.pro keeps NewPipeExtractor and Rhino whole.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Player
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    // UI
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    // collectAsStateWithLifecycle: screens in the background stop collecting (and recomputing)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    // Images (thumbnails, avatars), fetched with the app's own OkHttp client (see App).
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    // Media3 depends on Guava 33.3.1, which has a known vulnerability
    // (GHSA-xxph-c9ww-hj94, fixed in 33.7.2): use a fixed one. Guava stays compatible
    // within a major version. Remove once Media3 depends on 33.7.2 or newer
    // (the dependency scan, .github/scripts/osv-deps.py, will say if it comes back).
    constraints {
        implementation("com.google.guava:guava:33.8.0-android") {
            because("GHSA-xxph-c9ww-hj94 in the Guava that Media3 depends on")
        }
    }

    // Unit tests (app/src/test): plain JVM tests of the logic that doesn't need Android.
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is only a stub in JVM tests: the real one, for the files' format.
    testImplementation("org.json:json:20260814")
}

/**
 * Android only installs an update with a higher versionCode, so it's derived from
 * the version: 1.2.3 → 1_002_003. Room for 999 minors and patches per major.
 */
fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(version)?.destructured
        ?: error("antennaVersion must look like 1.2.3, got \"$version\"")
    require(minor.toInt() < 1000 && patch.toInt() < 1000) { "minor and patch must be below 1000: $version" }
    return major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
}
