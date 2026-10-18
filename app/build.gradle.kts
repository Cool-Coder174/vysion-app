import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.vysion.xr"
    compileSdk = libs.versions.compileSdk.get().toInt()   // 37: Compose 1.12 + Compose for XR beta01 (docs/stack.md §1)

    defaultConfig {
        applicationId = "com.vysion.xr"
        minSdk = libs.versions.minSdk.get().toInt()        // 34: Galaxy XR = Android 14; XR APIs need 34 at runtime
        targetSdk = libs.versions.targetSdk.get().toInt()  // 36: explicit, AGP 9 would default it to compileSdk
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }          // Galaxy XR + S25 Ultra are arm64; drops ExecuTorch's x86_64 .so

        // ADR-0008 / AppGraph.kt: -PuseFakes=true wires the Fake* modules (laptop emulator, S25 Ultra without models).
        buildConfigField("boolean", "USE_FAKES", (project.findProperty("useFakes")?.toString() ?: "false"))

        // Which detector lockfile row the engine tries first; the FP32 row "detector" is always the fallback.
        // -Pdetector=detector-int8 builds with the int8 export (docs/benchmarks.md). Shipping default: FP32.
        buildConfigField("String", "DETECTOR", "\"${project.findProperty("detector")?.toString() ?: "detector"}\"")

        // ADR-0010 cloud Describe: GEMINI_API_KEY from the gitignored local.properties (or the environment); never
        // committed. Blank = cloud Describe "not set up" and Describe stays on the headset. The key ends up inside the
        // APK, so restrict it to the Generative Language API in Cloud Console.
        val geminiKey = rootProject.file("local.properties").takeIf { it.isFile }
            ?.let { file -> Properties().apply { file.inputStream().use { load(it) } }.getProperty("GEMINI_API_KEY") }
            ?: System.getenv("GEMINI_API_KEY")
            ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"${geminiKey.trim().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // if ever enabled: add compileOnly("com.android.extensions.xr:extensions-xr:1.3.0") (stack.md §1)
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17   // built-in Kotlin takes jvmTarget from this; no kotlinOptions {} block
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "pte"   // uncompressed asset → fast copy to filesDir; ExecuTorch loads only real file paths (A-11)
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true   // android.util.Log etc. in JVM tests of stubs
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.kotlinx.coroutines.android)

    // R1: two camera streams (ImageAnalysis 640x480 + ImageCapture still) on camera id 0
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // R1/R3: Jetpack XR (Compose for XR panels, SceneCore spatial audio, ARCore for Jetpack XR perception)
    implementation(libs.androidx.xr.runtime)
    implementation(libs.androidx.xr.scenecore)
    implementation(libs.androidx.xr.compose)
    implementation(libs.androidx.xr.arcore)

    // R2: ExecuTorch runtime, XNNPACK flavor, includes LlmModule. No network permission in its manifest (ADR-0003).
    implementation(libs.executorch.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
