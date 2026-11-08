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

    buildFeatures { buildConfig = true }
    lint { checkReleaseBuilds = false }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("lib/arm64-v8a/libc++_shared.so", "lib/armeabi-v7a/libc++_shared.so")
            // DJI-Bibliotheken dürfen nicht gestrippt werden (sonst UnsatisfiedLinkError beim SDK-Start).
            keepDebugSymbols += listOf("**/*.so")
        }
        resources { excludes += listOf("META-INF/rxjava.properties") }
    }
}

dependencies {
    implementation("com.dji:dji-sdk-v5-aircraft:5.20.1")
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:5.20.1")
    runtimeOnly("com.dji:dji-sdk-v5-networkImp:5.20.1")
    implementation("com.dji:wpmzsdk:1.0.5.2")

    // Laufzeit-Bibliotheken, die das DJI SDK erwartet (wie in DJIs Beispiel-App)
    implementation("com.squareup.okhttp3:okhttp:3.14.9")
    implementation("io.reactivex.rxjava3:rxandroid:3.0.0")
    implementation("io.reactivex.rxjava3:rxkotlin:3.0.0")
    implementation("io.reactivex.rxjava2:rxjava:2.2.11")
    implementation("io.reactivex.rxjava2:rxandroid:2.1.1")
    implementation("com.google.code.gson:gson:2.9.1")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.3.1")
    implementation("androidx.lifecycle:lifecycle-process:2.3.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.3.1")
    implementation("androidx.lifecycle:lifecycle-livedata:2.3.1")
    implementation("androidx.room:room-runtime:2.4.1")
    implementation("androidx.room:room-rxjava3:2.4.1")
    implementation("com.iqiyi.xcrash:xcrash-android-lib:3.1.0")
    implementation("org.dom4j:dom4j:2.1.1")
    implementation("com.squareup.wire:wire-runtime:2.2.0")
    implementation("net.zetetic:sqlcipher-android:4.11.0")
    implementation("org.aspectj:aspectjrt:1.9.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media:media:1.0.0")
    implementation("androidx.fragment:fragment-ktx:1.3.3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Kleine Karte (OpenStreetMap-Kacheln): Drohne, Startpunkt, Wegpunkte, Handy
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
