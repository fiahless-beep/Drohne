plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// DJI App Key: nie ins Repo. Kommt aus ~/.gradle/gradle.properties (DJI_API_KEY) oder Umgebungsvariable.
val djiApiKey: String = (findProperty("DJI_API_KEY") as String?) ?: System.getenv("DJI_API_KEY") ?: "MISSING_KEY"

android {
    namespace = "com.felixos.drohne"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.felixos.drohne"
        minSdk = 24
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        manifestPlaceholders["API_KEY"] = djiApiKey
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Fester Schlüssel, damit neue Versionen sich über alte installieren lassen.
    val ks = rootProject.file("keystore/drohne.keystore")
    signingConfigs {
        create("drohne") {
            storeFile = rootProject.file("keystore/drohne.keystore")
            storePassword = "drohne-app"
            keyAlias = "drohne"
            keyPassword = "drohne-app"
        }
    }

    buildTypes {
        val sign = if (ks.exists()) signingConfigs.getByName("drohne") else signingConfigs.getByName("debug")
        debug { signingConfig = sign }
        release {
            isMinifyEnabled = false
            signingConfig = sign
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint { checkReleaseBuilds = false }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("lib/arm64-v8a/libc++_shared.so", "lib/armeabi-v7a/libc++_shared.so")
            keepDebugSymbols += listOf("*/*/libconstants.so", "*/*/libdji_innertools.so", "*/*/libdjibase.so",
                "*/*/libDJICSDKCommon.so", "*/*/libDJIFlySafeCore-CSDK.so", "*/*/libdjifs_jni-CSDK.so",
                "*/*/libDJIRegister.so", "*/*/libdjisdk_jni.so", "*/*/libDJIUpgradeCore.so",
                "*/*/libDJIUpgradeJNI.so", "*/*/libDJIWaypointV2Core-CSDK.so", "*/*/libdjiwpv2-CSDK.so",
                "*/*/libFlightRecordEngine.so", "*/*/libvideo-framing.so", "*/*/libwaes.so",
                "*/*/libagora-rtsa-sdk.so", "*/*/libc++.so", "*/*/libc++_shared.so", "*/*/libmrtc_28181.so",
                "*/*/libmrtc_agora.so", "*/*/libmrtc_core.so", "*/*/libmrtc_core_jni.so", "*/*/libmrtc_data.so",
                "*/*/libmrtc_log.so", "*/*/libmrtc_onvif.so", "*/*/libmrtc_rtmp.so", "*/*/libmrtc_rtsp.so")
        }
        resources { excludes += listOf("META-INF/rxjava.properties") }
    }
}

dependencies {
    implementation("com.dji:dji-sdk-v5-aircraft:5.20.1")
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:5.20.1")
    runtimeOnly("com.dji:dji-sdk-v5-networkImp:5.20.1")
    implementation("com.dji:wpmzsdk:1.0.5.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
