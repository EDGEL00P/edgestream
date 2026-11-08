plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.edgestream.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.edgestream.tv"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.1"
        // The app ships one language. AndroidX translations are the biggest part of the
        // resource table after code shrinking; drop the ones nothing can ever display.
        androidResources.localeFilters += listOf("en")
    }

    // Per-ABI APKs: each device downloads one copy of the 16 MB captioning engine instead of
    // two. The universal APK is not useful for sideloading and only doubles the download.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packaging {
        // Licence and version files are build metadata, not runtime resources.
        resources.excludes += listOf(
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.version",
            "META-INF/*.md",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
        )
        jniLibs.useLegacyPackaging = false
    }

    buildTypes {
        debug {
            // Fast iteration, but not sloppy: native code undebuggable (no overhead),
            // profileable so baseline profiles apply exactly as in release.
            isDebuggable = true
            isJniDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            // Optimised daily build: minified, shrunk, stripped — measurably faster/smaller.
            isDebuggable = false
            isJniDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-signed so a release build can be sideloaded straight onto a TV.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time on minSdk 24 devices.
        isCoreLibraryDesugaringEnabled = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Drop Kotlin's runtime null-check intrinsics; the type system already guarantees them.
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_stability.conf"))
    // `./gradlew :app:assembleRelease -PcomposeReports` writes stability and skippability
    // reports to app/build/compose_compiler for spotting recomposition hotspots.
    if (project.hasProperty("composeReports")) {
        reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
        metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Frozen at 1.7.x upstream but still the full Material icon set; R8 strips unused icons.
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    // Sideloaded builds get no Play-delivered profiles: this installs Compose's bundled profiles
    // and src/main/baseline-prof.txt at install time, so the UI runs compiled from the first launch.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Android TV home screen: Continue watching and the Live TV channel row.
    implementation("androidx.tvprovider:tvprovider:1.0.0")

    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // FFmpeg software decoders (DTS, TrueHD, FLAC, ALAC, Opus, Vorbis, MP2…) as a fallback behind the
    // SHIELD's hardware decoders and passthrough. GPL-3.0 (owner approved 2026-11-08); version tracks media3.
    implementation("io.github.anilbeesetti:nextlib-media3ext:$media3-0.16.0")
    // Media session: what launchers read for their Continue watching and Live TV rows.
    implementation("androidx.media3:media3-session:$media3")

    val room = "2.8.5"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    // Whole-database encryption at rest (providers, profiles, calendar, guide); key sealed by the Keystore.
    implementation("net.zetetic:sqlcipher-android:4.19.1@aar")
    implementation("androidx.sqlite:sqlite:2.6.2")

    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    implementation("com.google.zxing:core:3.5.3")

    // Offline speech recognition for auto-generated captions.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.19.1@aar")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the Android jar only has stubs).
    testImplementation("org.json:json:20240303")
}
