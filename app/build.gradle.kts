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
        // The first release's version. After that, CI passes the release's version
        // (-PantennaVersion=1.2.3, from the git tag); see .github/workflows/ci.yaml.
        versionName = (findProperty("antennaVersion") as String?) ?: "0.1.0"
        versionCode = versionCodeOf(versionName!!)
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
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

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
    // collectAsStateWithLifecycle: screens in the background stop collecting (and recomputing)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
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
