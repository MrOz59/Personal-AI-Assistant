import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.naomi.assistant"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.naomi.assistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Read the Gemini key from local.properties (kept out of git) and expose it as
        // BuildConfig.GEMINI_API_KEY so it never lives in source code.
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProps.getProperty("GEMINI_API_KEY", "")}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${localProps.getProperty("GROQ_API_KEY", "")}\"")

        // Optional ABI_FILTERS=arm64-v8a in local.properties packages native libs for just those
        // ABIs — roughly halves the APK when sideloading to your own phone. Unset = all ABIs.
        localProps.getProperty("ABI_FILTERS")?.let { abis ->
            ndk { abiFilters += abis.split(",").map { it.trim() } }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // android.util.Log & co. return defaults in JVM unit tests instead of throwing, so tests can
    // drive real app classes (e.g. LiveBrainTest → CloudBrain.respond).
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")                       // Gemini REST
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")   // async
    implementation("net.java.dev.jna:jna:5.13.0@aar")                          // Vosk dep
    implementation("com.alphacephei:vosk-android:0.3.47")                      // offline wake word
    implementation("com.google.mediapipe:tasks-genai:0.10.24")                 // on-device LLM
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")      // speaker verification (ECAPA-TDNN)
    implementation("com.google.android.gms:play-services-location:21.3.0")      // one-tap "turn on location" dialog
    testImplementation(libs.junit)
    // Android's own org.json for unit tests (android.jar only has stubs). Unlike json.org's, it keeps
    // key order like the device does — which matters for the JSON Schemas we send.
    testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}