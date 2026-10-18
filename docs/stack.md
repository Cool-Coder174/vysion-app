# Stack pins and API cheat-sheets

**How this page was made (2026-10-17, skeleton PR).** Four research agents fetched every version, coordinate, class name and signature live from Maven metadata, developer.android.com reference pages, the AndroidX API files and the ExecuTorch repository at the pinned tag. Two adversarial verifiers per topic (primary-source and API-correctness) then tried to refute each claim; a synthesizer resolved disagreements by fetching the source again (§0). Anything no verifier confirmed is tagged **UNVERIFIED**. The build in `gradle/libs.versions.toml` and the manifest in `app/src/main/AndroidManifest.xml` are copies of §2; if they drift from this page, this page has the bug.

**Nothing on this page has run on a Galaxy XR.** §5 lists the yes/no questions to settle on the device Saturday; `docs/assumptions.md` carries the SRS's own list. When a build or a device run proves a line wrong, fix the line and append a row to "Corrections found at build time" at the bottom so the next reader knows which is newer.

Verified-by codes: ET = executorch-android topic, XR = jetpack-xr topic, BS = build-stack topic, DV = device-platform-speech topic; PS = primary-source lens, AC = api-correctness lens; SYN = synthesizer's own fetch.

---


## 0. Decisions on disputed or conflicting pins

| # | Item | Option A | Option B | Decision | Why (evidence) |
|---|---|---|---|---|---|
| D1 | `org.pytorch:executorch-android` | 1.3.1 (ET researcher, SRS "current 1.3.x") | 1.5.1 (BS researcher, Maven `<latest>`) | **1.4.0** (was 1.3.1; bumped 2026-10-17, see Corrections) | Maven has 1.3.1 / 1.4.0 / 1.5.1 only (SYN curl). Every R2 signature in section 3 was verified line-by-line at tag v1.3.1 (ET-PS, ET-AC); 1.4+ is a Kotlin rewrite that keeps every name (ET-AC, BS-PS), so bumping is a one-line change. Neither version can run the Gemma 4 .pte through `LlmModule` (D5), so 1.5.1 buys nothing for the VLM; its only relevant addition is `extension.image.ImageProcessor` for YOLO letterboxing. Python export must match: `pip install executorch==1.3.1`. |
| D2 | `androidx.xr.runtime:runtime` | 1.0.0-rc02 (XR researcher) | 1.1.0-alpha01 (BS researcher, set-up-sdk page) | **1.0.0-rc02** | scenecore-rc02 and arcore-rc02 POMs pin runtime 1.0.0-rc02 (SYN curl); 1.1.0-alpha01's only change is `SPATIAL_API_V4` in `SpatialApiVersions` (XR-PS). compose-beta01 classes.jar links against rc02 jars with 0 missing refs (XR-AC). |
| D3 | `minSdk` | 24 (Jetpack XR AAR floor, XR-PS) | 34 (BS/DV policy) | **34** | Galaxy XR is Android 14 / API 34 on every published build (Samsung changelog, SYN curl + DV-PS); S25 Ultra is API 36 or 37. XR APIs need API 34 at runtime (xr-compose 1.0.0-alpha02 notes, DV-PS/AC). minSdk 34 lets `PackageInfoFlags` (33), `triggerModelDownload(…, ModelDownloadListener)` (34) run unguarded; `Debug.getRss()` (35) must still be guarded. |
| D4 | `android.software.xr.api.spatial` `required` | `"true"` (dedicated XR APK, XR researcher) | `"false"` (same APK on S25 Ultra, BS researcher) | **`"false"`** | SRS keeps the S25 Ultra as fallback demo device from one codebase; both values are on the build-immersive page (XR-PS, BS-PS). |
| D5 | Gemma 4 .pte via `LlmModule` | SRS: "load with the Android multimodal runner" | ET: not loadable at 1.3.1, 1.5.1 or main | **Not loadable; SRS line is wrong as written** | `MultimodalPrefiller::load()` hard-requires methods `token_embedding` + `text_decoder`; the gemma4 export has no `token_embedding` and a 3-input `text_decoder` (ET-PS, ET-AC). Real failure: `Program: "No method named 'token_embedding' in program"` → `Error::InvalidArgument` (0x12 = 18); the string "Missing required methods (…)" does not exist (ET-PS). R2 options in section 3-R2. |
| D6 | CameraX `Camera2CameraInfo` opt-in | "no `@OptIn` needed" (BS researcher) | lint-enforced opt-in (both BS verifiers) | **`@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)`** | `ExperimentalCamera2Interop` is `@androidx.annotation.RequiresOptIn` (level ERROR); `./gradlew :app:lintDebug` fails with `UnsafeOptInUsageError` without it; kotlinc ignores the Java marker, which is why `assembleDebug` passed (BS-PS, BS-AC). |
| D7 | `Session.create(this@MainActivity)` | positional Activity arg (XR researcher) | named `context =` arg (XR-AC) | **`Session.create(context = activity)` inside `withContext(Dispatchers.IO)`** | rc02 bytecode still ships a `@Deprecated @RestrictTo(LIBRARY)` non-suspend `create(activity: Activity, …)` built on `runBlocking`; Kotlin prefers the more specific `Activity` overload, so the positional call binds to it (XR-AC, javap). |
| D8 | `androidx.xr.compose.material3:material3:1.0.0-alpha17` | omit as "runtime-risk mix" (XR-PS) | linkable, 0 missing refs vs compose beta01 (XR-AC) | **Omit (not needed)**; safe to add later | Its POM pins compose 1.0.0-alpha14, but a javap cross-check resolves every member ref against beta01 (XR-AC). |
| D9 | Galaxy XR OS version | "not published" (DV researcher) | Android 14 per Samsung changelog (DV-PS) | **Android 14 / API 34, high confidence** | https://doc.samsungmobile.com/SM-I610/036371251209/eng.html prints `Android version : U(Android 14)` on all four builds; latest `I610UEU2AZF3` 2026-07-07, SPL 2026-06-01 (SYN curl). |
| D10 | Kotlin 2.4.21 with AGP 9.4.1 | tested matrix is AGP ≤ 9.3.1 (kotlinlang table) | empirically built (BS researcher) | **2.4.21**, fallback `kotlin = "2.2.10"` | Scaffold built green with AGP 9.4.1 + Gradle 9.6.1 + KGP 2.4.21 lifted via the compose plugin POM (BS-PS, BS-AC). Every AAR's Kotlin metadata ≤ 2.2.0, so AGP's bundled 2.2.10 can compile the whole stack if the lift misbehaves. |

Note on the verified build: BS's green `assembleDebug testDebugUnitTest` used executorch 1.5.1 and runtime
1.1.0-alpha01; this document pins 1.3.1 and rc02 (D1, D2). Both resolve from the same two repositories and
both alternates were independently resolved by XR/ET verifiers, but the exact pinned set was not built end
to end by anyone. First `./gradlew assembleDebug` on Sat 10:30 is the check (section 5, Q1).

---

## 1. Pinned versions table

| Artifact / tool | Version | Source URL (fetched) | Verified-by |
|---|---|---|---|
| Android Gradle Plugin `com.android.application` | **9.4.1** (last non-alpha; `<latest>` says 9.5.0-alpha10, ignore it) | https://dl.google.com/android/maven2/com/android/application/com.android.application.gradle.plugin/maven-metadata.xml | BS-PS, BS-AC |
| AGP requirements | Gradle ≥ 9.6.0, JDK ≥ 17, Build-Tools 36.0.0, max API 37 | https://developer.android.com/build/releases/gradle-plugin | BS-PS, BS-AC |
| Gradle wrapper | **9.6.1** (final, 2026-06-26; current is 9.8.1) | https://services.gradle.org/versions/all | BS-PS, BS-AC |
| JDK | **17** (Gradle 9.6.1 page: JVM 17–26; AGP min 17) | https://docs.gradle.org/9.6.1/userguide/compatibility.html | BS-PS, BS-AC |
| Kotlin / KGP | **2.4.21** (2026-10-08; 2.5.0-Beta1 is pre-release) | https://repo1.maven.org/maven2/org/jetbrains/kotlin/plugin/compose/org.jetbrains.kotlin.plugin.compose.gradle.plugin/maven-metadata.xml | BS-PS, BS-AC |
| Compose compiler plugin `org.jetbrains.kotlin.plugin.compose` | **2.4.21** (= Kotlin version; its POM pulls kotlin-gradle-plugin 2.4.21) | https://repo1.maven.org/maven2/org/jetbrains/kotlin/compose-compiler-gradle-plugin/2.4.21/compose-compiler-gradle-plugin-2.4.21.pom | BS-PS, BS-AC |
| `org.jetbrains.kotlin.android` plugin | **do not apply** (AGP 9 built-in Kotlin, bundles KGP 2.2.10) | https://developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes | BS-PS, BS-AC, XR-AC |
| `androidx.compose:compose-bom` | **2026.09.00** (ui/foundation/runtime 1.12.1, material3 1.4.0) | https://dl.google.com/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom | BS-PS, BS-AC |
| `androidx.activity:activity-compose` | **1.13.0** | https://dl.google.com/android/maven2/androidx/activity/activity-compose/maven-metadata.xml | BS-PS, BS-AC |
| `androidx.lifecycle:lifecycle-runtime-compose`, `lifecycle-viewmodel-compose` | **2.11.0** | https://dl.google.com/android/maven2/androidx/lifecycle/lifecycle-runtime-compose/maven-metadata.xml | BS-PS, BS-AC |
| `androidx.core:core-ktx` | **1.19.1** | https://dl.google.com/android/maven2/androidx/core/core-ktx/maven-metadata.xml | BS-PS, BS-AC |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android`, `-test` | **1.11.0** | https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/maven-metadata.xml | BS-PS, BS-AC |
| `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` | **1.6.2** (2026-08-26; 1.7.0-alpha03 deprecates `Camera2CameraInfo`, do not bump) | https://developer.android.com/jetpack/androidx/releases/camera | BS-PS, BS-AC |
| `androidx.xr.runtime:runtime` | **1.0.0-rc02** (D2) | https://dl.google.com/android/maven2/androidx/xr/runtime/runtime/maven-metadata.xml | XR-PS, XR-AC, SYN |
| `androidx.xr.scenecore:scenecore` | **1.0.0-rc02** (2026-10-07) | https://dl.google.com/android/maven2/androidx/xr/scenecore/scenecore/maven-metadata.xml | XR-PS, XR-AC, BS-PS |
| `androidx.xr.arcore:arcore` | **1.0.0-rc02** (2026-10-07; SRS's 1.0.0-alpha15 is 4 releases stale) | https://dl.google.com/android/maven2/androidx/xr/arcore/arcore/maven-metadata.xml | XR-PS, XR-AC, BS-PS, DV-PS |
| `androidx.xr.compose:compose` | **1.0.0-beta01** (2026-09-23) | https://dl.google.com/android/maven2/androidx/xr/compose/compose/maven-metadata.xml | XR-PS, XR-AC, BS-PS |
| `androidx.xr.compose.material3:material3` | 1.0.0-alpha17 — **not used** (D8) | https://dl.google.com/android/maven2/androidx/xr/compose/material3/material3/maven-metadata.xml | XR-PS, XR-AC |
| `androidx.xr.runtime:runtime-manifest` | 1.0.0-rc02 — transitive (compile dep of runtime); holds `ManifestPermission`/`ManifestProperty`/`ManifestFeature` constants | https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/xr/runtime/runtime-manifest/api/current.txt | XR-PS, XR-AC |
| `androidx.xr.runtime:runtime-openxr` | 1.0.0-rc02 — transitive; declares `<uses-native-library android:name="libopenxr.google.so" android:required="false"/>`; do not add manually | (AAR manifest, XR-PS) | XR-PS, XR-AC |
| `androidx.xr.runtime:runtime-testing` | 1.0.0-rc02 — **UNVERIFIED** (named by XR researcher, no verifier checked it); not in the build files | — | — |
| `com.android.extensions.xr:extensions-xr` | 1.3.0 `compileOnly`, **only if minify is enabled** (`implementation` breaks at runtime); Maven has 1.4.0 but the docs prescribe 1.3.0 | https://developer.android.com/develop/xr/jetpack-xr-sdk/set-up-sdk | XR-PS, BS-PS, BS-AC |
| XR AAR build constraints | compose-beta01 / scenecore-rc02 / material3-alpha17: `minCompileSdk=36`, `minAndroidGradlePluginVersion=8.9.1`; runtime/arcore rc02: 34 / 8.1.1; all AAR manifests `minSdkVersion=24`; all POMs kotlin-stdlib 2.1.20 | (aar-metadata.properties inside each AAR) | XR-PS, XR-AC |
| `org.pytorch:executorch-android` | **1.3.1** (D1; Maven also has 1.4.0, 1.5.1; 1.4.1/1.5.0 are GitHub tags with no AAR) | https://repo1.maven.org/maven2/org/pytorch/executorch-android/maven-metadata.xml | ET-PS, ET-AC, BS-PS, BS-AC, SYN |
| ExecuTorch AAR facts | xnnpack flavor; ABIs arm64-v8a + x86_64; AAR minSdk 23, compileSdk 34, native built for android-26; bundles `LlmModule` (LLM runner JNI); QNN = `org.pytorch:executorch-android-qnn:1.3.1`, Vulkan = `…-vulkan:1.3.1`; sha256 `a9acc1e85a7f8b45c06ecb0fea398ebc6a5ad474d53278c2058c060ba4f37d17` | https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/extension/android/executorch_android/build.gradle | ET-PS, ET-AC |
| `executorch` (PyPI, export env) | **1.3.1**, Python ≥3.10,<3.14; deps torch≥2.12.0a0, torchao≥0.17.0, pytorch-tokenizers≥1.3.0 | https://pypi.org/pypi/executorch/1.3.1/json | ET-PS, ET-AC |
| `junit:junit` | **4.13.2** | https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml | BS-PS, BS-AC |
| `androidx.test.ext:junit` | **1.3.0** | https://dl.google.com/android/maven2/androidx/test/ext/junit/maven-metadata.xml | BS-PS, BS-AC |
| `androidx.test.espresso:espresso-core` | **3.7.0** | https://dl.google.com/android/maven2/androidx/test/espresso/espresso-core/maven-metadata.xml | BS-PS, BS-AC |
| `androidx.compose.ui:ui-test-junit4`, `ui-test-manifest`, `ui-tooling` | from BOM (1.12.1) | (BOM POM above) | BS-AC |
| Test runner | `androidx.test.runner.AndroidJUnitRunner` (AGP 9 default) | https://developer.android.com/build/releases/past-releases/agp-9-0-0-release-notes | BS-PS, BS-AC |
| `actions/checkout` | **v7** (v7.0.1) | https://api.github.com/repos/actions/checkout/releases/latest | BS-PS, BS-AC |
| `actions/setup-java` | **v6** (v6.1.0; inputs `distribution: temurin`, `java-version: '17'`) | https://api.github.com/repos/actions/setup-java/releases/latest | BS-PS, BS-AC |
| `android-actions/setup-android` | **v4** (v4.0.4; `accept-android-sdk-licenses` default true, `cmdline-tools-version` default `15859902`) | https://raw.githubusercontent.com/android-actions/setup-android/main/action.yml | BS-PS, BS-AC |
| `gradle/actions/setup-gradle` | **v6** (v6.4.0) | https://github.com/gradle/actions/releases | BS-PS, BS-AC |
| `actions/upload-artifact` | **v7** (v7.0.2) | https://api.github.com/repos/actions/upload-artifact/releases/latest | BS-PS, BS-AC |
| SDK packages | `platforms;android-37.0`, `build-tools;36.0.0`, `platform-tools` | https://dl.google.com/android/repository/repository2-3.xml | BS-PS, BS-AC |
| compileSdk / minSdk / targetSdk | **37 / 34 / 36** (Compose 1.12 → compileSdk 37 + AGP 9; D3; AGP 9 defaults targetSdk to compileSdk when unset, so set it) | https://developer.android.com/develop/ui/compose/compiler | BS-PS, BS-AC, DV-PS |
| Android Studio | latest **Canary** (today: Android Studio Rabbit 2 \| 2026.2.2 Canary 5); XR tooling absent from stable | https://developer.android.com/develop/xr/jetpack-xr-sdk/get-studio ; https://developer.android.com/studio/preview | XR-PS, XR-AC |
| XR headset emulator image | `system-images;android-34;android-xr-preview-playstore;arm64-v8a` (API 34, "Google Play XR Preview API v4", rev 3); macOS 13.3+, Apple Silicon, 16 GB | https://dl.google.com/android/repository/sys-img/google_xr/sys-img2-4.xml | XR-PS, XR-AC |

---

## 2. Ready-to-paste files

### 2.1 `gradle/libs.versions.toml`

```toml
[versions]
agp = "9.4.1"
kotlin = "2.4.21"          # fallback "2.2.10" (AGP 9.4.1's bundled KGP) if the lift misbehaves (D10)
compileSdk = "37"
minSdk = "34"
targetSdk = "36"
composeBom = "2026.09.00"
activityCompose = "1.13.0"
lifecycle = "2.11.0"
coreKtx = "1.19.1"
coroutines = "1.11.0"
camerax = "1.6.2"
xrCompose = "1.0.0-beta01"
xrScenecore = "1.0.0-rc02"
xrArcore = "1.0.0-rc02"
xrRuntime = "1.0.0-rc02"   # D2: set-up-sdk page lists 1.1.0-alpha01; rc02 is what scenecore/arcore rc02 pin
executorch = "1.3.1"       # D1: 1.5.1 is a one-line bump; keep PyPI executorch == this value
junit4 = "4.13.2"
androidxTestJunit = "1.3.0"
espresso = "3.7.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
androidx-camera-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
androidx-camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
androidx-camera-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }
androidx-xr-compose = { group = "androidx.xr.compose", name = "compose", version.ref = "xrCompose" }
androidx-xr-scenecore = { group = "androidx.xr.scenecore", name = "scenecore", version.ref = "xrScenecore" }
androidx-xr-arcore = { group = "androidx.xr.arcore", name = "arcore", version.ref = "xrArcore" }
androidx-xr-runtime = { group = "androidx.xr.runtime", name = "runtime", version.ref = "xrRuntime" }
executorch-android = { group = "org.pytorch", name = "executorch-android", version.ref = "executorch" }
junit4 = { group = "junit", name = "junit", version.ref = "junit4" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestJunit" }
androidx-test-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

### 2.2 `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()        // androidx.xr.*, androidx.camera.*, compose
        mavenCentral()  // org.pytorch:executorch-android, kotlinx
    }
}
rootProject.name = "vysion-xr"
include(":app")
```

### 2.3 `build.gradle.kts` (root)

```kotlin
// AGP 9.x has built-in Kotlin: do NOT apply org.jetbrains.kotlin.android anywhere (D10).
// The compose plugin at 2.4.21 lifts the classpath KGP from AGP's bundled 2.2.10 to 2.4.21.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

### 2.4 `app/build.gradle.kts`

```kotlin
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

    // R2: ExecuTorch runtime, XNNPACK flavor, includes LlmModule. Declares no network permission of its own.
    implementation(libs.executorch.android)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
```

`app/proguard-rules.pro` (only matters if minify is turned on; the AAR's own proguard.txt already keeps
`@DoNotStrip`/native members, this is belt-and-braces):

```
-keep class org.pytorch.executorch.** { *; }
```

### 2.5 `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 -XX:+UseParallelGC
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

### 2.6 `gradle/wrapper/gradle-wrapper.properties`

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

Gradle 9 refuses to run without a settings file, so generate the wrapper (`gradle wrapper --gradle-version 9.6.1`)
after `settings.gradle.kts` exists. (The wrapper in this repo was generated from the cached Gradle 9.1.0 distribution and re-pointed at 9.6.1; the wrapper jar is version-agnostic.)

### 2.7 `app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Vysion XR manifest. Sources and verification for every entry: docs/stack.md §2.7.
  Privacy boundary (ADR-0003, amended by ADR-0010): network only for optional features with an on-device fallback.
  CI greps this file and the merged manifest.
-->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Jetpack XR feature flag. required="false" keeps ONE APK installable on the S25 Ultra fallback (ADR-0006).
         https://developer.android.com/develop/xr/jetpack-xr-sdk/build-immersive -->
    <uses-feature android:name="android.software.xr.api.spatial" android:required="false" />
    <uses-feature android:name="android.hardware.xr.input.hand_tracking" android:required="false" />
    <!-- CAMERA implies a camera feature; declare it optional so the APK is not filtered. -->
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <!-- World-facing camera (camera id 0) and the microphone for SpeechRecognizer. Both runtime permissions,
         requested with a spoken explanation (FR-G1, platform/Permissions.kt). -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <!-- Android XR permissions. All "dangerous": declared here AND requested at runtime.
         https://developer.android.com/develop/xr/permissions
         https://developer.android.com/reference/androidx/xr/runtime/manifest/ManifestPermission -->
    <uses-permission android:name="android.permission.SCENE_UNDERSTANDING_COARSE" /> <!-- planes, anchors, hit tests (R3) -->
    <uses-permission android:name="android.permission.SCENE_UNDERSTANDING_FINE" />   <!-- depth maps: proximity (FR-N1) -->
    <uses-permission android:name="android.permission.HAND_TRACKING" />              <!-- pinch-and-hold push-to-talk (FR-G2, P1) -->
    <uses-permission android:name="android.permission.HEAD_TRACKING" />              <!-- DeviceTrackingMode.SPATIAL → head pose (FR-N3/N4) -->

    <!-- Network is allowed for optional features, each with an on-device fallback (ADR-0010, amends ADR-0003).
         Read, Describe and Find the door always run on the headset; nothing here may make them depend on a
         connection. ACCESS_NETWORK_STATE lets a feature skip the network at once when offline. -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- "Where am I" (FR-N9, ADR-0010 row "Where am I map"): network location (Wi-Fi through Play services; the Galaxy XR
         has no GPS). Requested only the first time the wearer asks, never at launch; both names are needed for the
         precise-location dialog. The fix is never stored or logged. -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

    <!-- Package visibility (targetSdk ≥ 30): the on-device SpeechRecognizer service and the TextToSpeech engines. -->
    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
        <intent>
            <action android:name="android.intent.action.TTS_SERVICE" />
        </intent>
    </queries>

    <application
        android:name=".VysionApplication"
        android:label="@string/app_name"
        android:allowBackup="false"
        android:theme="@android:style/Theme.Material.NoActionBar">

        <!-- Launch straight into Full Space (Jetpack XR managed) with passthrough (FR-G1).
             Other documented value: XR_ACTIVITY_START_MODE_HOME_SPACE.
             https://developer.android.com/develop/xr/jetpack-xr-sdk/build-immersive -->
        <property
            android:name="android.window.PROPERTY_XR_ACTIVITY_START_MODE"
            android:value="XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED" />
        <!-- The user walks to a door: ask for the large boundary. -->
        <property
            android:name="android.window.PROPERTY_XR_BOUNDARY_TYPE_RECOMMENDED"
            android:value="XR_BOUNDARY_TYPE_LARGE" />

        <!-- configChanges: rotating the S25 Ultra (2D fallback) or resizing the Home Space window must not run
             onStop/onStart (which cancels an in-flight Read and re-speaks the welcome); Compose recomposes in
             place and the XR Session keeps its Activity. -->
        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|density|keyboardHidden"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Manifest notes (verified): ADR-0010 (2026-10-17) replaced the two `tools:node="remove"` guards this page used to
show with plain `INTERNET` and `ACCESS_NETWORK_STATE` declarations. `ACCESS_NETWORK_STATE` also arrives transitively
from `androidx.media3:media3-exoplayer` (pulled in by SceneCore; we never use ExoPlayer), and the merged debug APK
carried `WAKE_LOCK` and `com.vysion.xr.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (BS-AC aapt2, 2026-10-17). Any other
network permission (`ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE`, …) is a bug: `scripts/check_privacy.sh` (CI, source and
merged manifests) fails on it, and `ManifestAudit` prints what the APK requests on the overlay (`ACCESS_NETWORK_STATE,
INTERNET` is the expected value; see "Corrections found at build time"). The `configChanges` attribute keeps rotation (S25 Ultra) and Home Space
resizes from recreating the activity. Lint warnings you will see and may ignore: `OldTargetApi` (36 vs 37),
`MissingApplicationIcon` (add `android:icon` once a mipmap exists), `allowBackup` deprecated (use
`dataExtractionRules` later), `ChromeOsAbiSupport` (expected with arm64-only).
`android.permission.HEAD_TRACKING` is NOT on the /develop/xr/permissions overview page; its sources are the
`ManifestPermission` reference ("Protection level: dangerous") and the device-pose guide (XR-PS, XR-AC).

### 2.8 `.github/workflows/ci.yml`

```yaml
# CI: the merge gate for the 15-minute cadence (docs/schedule.md).
# A branch is "green" when the JVM unit tests pass, the debug APK assembles, and the privacy gate
# is clean on the MERGED manifest (ADR-0003). Nothing here needs a device or a model file.
# Action versions: docs/stack.md §1 (verified 2026-10-17).
name: ci

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 40
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '17'

      - uses: android-actions/setup-android@v4
        with:
          packages: 'platform-tools platforms;android-37.0 build-tools;36.0.0'

      - uses: gradle/actions/setup-gradle@v6

      - name: JVM unit tests
        run: ./gradlew --console=plain --no-daemon testDebugUnitTest

      - name: Assemble debug APK
        run: ./gradlew --console=plain --no-daemon assembleDebug

      - name: Privacy gate (source + merged manifest, frame persistence, network SDKs)
        run: bash scripts/check_privacy.sh

      - name: Cue placement gate (every spoken string lives in voice/Cues.kt)
        run: if [ -x scripts/check_cues.sh ]; then bash scripts/check_cues.sh; else echo "scripts/check_cues.sh not present yet (R4)"; fi

      - name: Lint (report only until docs/stack.md Q17 is closed)
        run: ./gradlew --console=plain --no-daemon :app:lintDebug
        continue-on-error: true

      - name: Upload debug APK
        if: success()
        uses: actions/upload-artifact@v7
        with:
          name: vysion-xr-debug-apk
          path: app/build/outputs/apk/debug/*.apk
          retention-days: 7

      - name: Upload unit test and lint reports
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: reports
          path: |
            app/build/reports/tests/testDebugUnitTest
            app/build/reports/lint-results-debug.html
          retention-days: 7
```

This is the repo's real `ci.yml`: unit tests, assemble, the privacy gate (after assemble, so it sees the merged
manifest), the cue gate and a report-only lint. The GitHub required-check context for branch protection is the job
name, `build`. ci.yml was not executed by anyone yet (no runner); the local equivalent
`assembleDebug testDebugUnitTest` plus `scripts/check_privacy.sh` and `scripts/check_cues.sh` was. Lint is `continue-on-error` because the scaffold as first built failed lint on exactly the two
items fixed above (D6 opt-in, camera uses-feature); once those are in, consider removing the flag.

---

## 3. API cheat-sheet per role

All Kotlin imports are one per line (the researcher's `import a.b.{X, Y}` form is Scala and will not compile).

### R1 — camera, Compose for XR, passthrough, Full Space

Sources: CameraX https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2CameraInfo ,
https://developer.android.com/reference/androidx/camera/core/ImageProxy ,
https://developer.android.com/reference/kotlin/androidx/camera/lifecycle/package-summary ;
Compose for XR API file https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/xr/compose/compose/api/1.0.0-beta01.txt ;
guides https://developer.android.com/develop/xr/jetpack-xr-sdk/develop-ui ,
https://developer.android.com/develop/xr/jetpack-xr-sdk/check-spatial-capabilities ,
https://developer.android.com/develop/xr/jetpack-xr-sdk/add-environments ,
https://developer.android.com/develop/xr/jetpack-xr-sdk/transition-home-space-to-full-space .

| Fact | Exact string | Src |
|---|---|---|
| World camera id | Google (Feb 2025, press-relayed): `camera_id=0` = world-facing stream via Camera2/CameraX with normal `CAMERA` permission; `camera_id=1` = avatar stream. No developer.android.com page documents headset camera access as of 2026-10-17; **unverified on device** | https://skarredghost.com/2025/02/07/android-xr-camera-access-2/ (secondary) |
| Camera id filter | `Camera2CameraInfo.from(cameraInfo).getCameraId(): String` (camera-camera2); class is `@ExperimentalCamera2Interop` → annotate usage `@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)` (D6) | BS-PS, BS-AC |
| Resolution | `ImageAnalysis.Builder().setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(640,480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())`; `setTargetResolution` is deprecated | BS-PS, BS-AC |
| Output format | `setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)` (=2; YUV_420_888=1, NV21=3); `setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)` (=0) | BS-PS, BS-AC |
| Bitmap | `ImageProxy.toBitmap()` supports YUV_420_888, JPEG, RGBA_8888; throws IllegalArgumentException otherwise, UnsupportedOperationException if conversion fails; rotation via `imageInfo.rotationDegrees` | BS-AC |
| Still | `ImageCapture.takePicture(Executor, ImageCapture.OnImageCapturedCallback)`; `CAPTURE_MODE_MINIMIZE_LATENCY` = 1 | BS-PS, BS-AC |
| Provider | `suspend fun ProcessCameraProvider.Companion.awaitInstance(context)` needs `import androidx.camera.lifecycle.awaitInstance`; `bindToLifecycle(LifecycleOwner, CameraSelector, vararg UseCase)` | BS-PS, BS-AC |

```kotlin
package com.vysion.xr
import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FrameSource(private val context: Context) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val _frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames: Flow<Bitmap> = _frames.asSharedFlow()

    /** Camera2 id "0" = world-facing per Google's statement. Change the literal here after the Sat 11:00 probe. */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    val worldCameraSelector: CameraSelector = CameraSelector.Builder()
        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).getCameraId() == "0" } }
        .build()

    private val analysis: ImageAnalysis = ImageAnalysis.Builder()
        .setResolutionSelector(ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build())
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also { ia -> ia.setAnalyzer(analysisExecutor) { image: ImageProxy -> image.use { _frames.tryEmit(it.toBitmap()) } } }

    private val still: ImageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()

    suspend fun bind(owner: LifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        provider.unbindAll()
        provider.bindToLifecycle(owner, worldCameraSelector, analysis, still)
    }

    suspend fun captureStill(): Bitmap = suspendCancellableCoroutine { cont ->
        still.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) { cont.resume(image.use { it.toBitmap() }) }
            override fun onError(exception: ImageCaptureException) { cont.resumeWithException(exception) }
        })
    }
}
```
Hour-one probe: log `CameraManager.cameraIdList` and `Camera2CameraInfo.from(camera.cameraInfo)` characteristics;
if id "0" yields nothing, open ids directly with Camera2 (`CameraSelector.DEFAULT_BACK_CAMERA` selects
`LENS_FACING_BACK`, not literally id 0).

Compose for XR 1.0.0-beta01 (XR-PS, XR-AC, all names checked in classes.jar + API file):

| Need | Exact API |
|---|---|
| Session in Compose | `androidx.xr.compose.platform.LocalSession: ProvidableCompositionLocal<Session?>` (null until the Session exists, then recomposes) |
| XR vs 2D | `LocalSpatialConfiguration.current.hasXrSpatialFeature` or `SpatialConfiguration.hasXrSpatialFeature(context)`; there is **no** `LocalHasXrSpatialFeature` |
| Capabilities | `LocalSpatialCapabilities.current.isSpatialUiEnabled / isContent3dEnabled / isAppEnvironmentEnabled / isPassthroughControlEnabled / isSpatialAudioEnabled` |
| Containers | `androidx.xr.compose.spatial.Subspace(modifier, content: SpatialBoxScope.() -> Unit)`; `androidx.xr.compose.subspace.SpatialPanel(modifier: SubspaceModifier, shape: SpatialShape, interactionPolicy: InteractionPolicy?, content)`; `SpatialRow`, `SpatialColumn`, `SpatialBox`, `SpatialMainPanel` |
| Modifiers | `androidx.xr.compose.subspace.layout.*`: `SubspaceModifier.width(dp).height(dp).size().offset().padding().movable().resizable().fillMaxWidth()` |
| Orbiter | `Orbiter(position: OrbiterPosition, shape: SpatialShape = …, content)` with `OrbiterPosition.BottomCenter()`; the `OrbiterAlignment`/`OrbiterAnchorPoint`/`ContentEdge` overloads are `@Deprecated` (develop-ui still shows them) |
| Removed in beta01 | `DragPolicy` (use `movable()`), old `ResizePolicy`, `SurfaceProtection`; `Meter` went in alpha17 |
| Full Space | `session.scene.requestFullSpace()` / `requestHomeSpace()` (scenecore; `requestFullSpaceMode` renamed in alpha16) or `suspend ComponentActivity.requestFullSpace(): SpaceRequestResult` (Success / Unsupported / Error(cause), sealed nonexhaustive) |
| Passthrough | `session.scene.spatialEnvironment.preferredPassthroughOpacity = 1.0f` (Float; `SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE` releases); `currentPassthroughOpacity`; `addPassthroughOpacityChangedListener(Consumer<Float>)`; gate on `session.scene.spatialCapabilities.contains(SpatialCapability.PASSTHROUGH_CONTROL)`. The add-environments doc page is stale (old names); the binary wins |
| Drawing in passthrough | `SpatialExternalSurface(stereoMode = StereoMode.Mono, modifier) { }` for a Surface you draw into (XR researcher; names not re-checked by verifiers — **UNVERIFIED**) |

```kotlin
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.LocalSpatialConfiguration
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterPosition
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialRow
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.scene

@Composable fun VysionRoot() {
    val isXr = LocalSpatialConfiguration.current.hasXrSpatialFeature
    val caps = LocalSpatialCapabilities.current
    if (isXr && caps.isSpatialUiEnabled) {
        Subspace {
            SpatialRow {
                SpatialPanel(SubspaceModifier.width(1400.dp).height(824.dp).movable().resizable()) {
                    TranscriptPanel()                                         // 48dp+ text, #FFFFFF on #000000 = 21:1
                    Orbiter(position = OrbiterPosition.BottomCenter()) { ControlBar() }
                }
            }
        }
    } else FlatUi()                                                           // S25 Ultra fallback
}

fun forcePassthrough(session: Session) {
    val env = session.scene.spatialEnvironment
    if (session.scene.spatialCapabilities.contains(SpatialCapability.PASSTHROUGH_CONTROL)) {
        env.preferredPassthroughOpacity = 1.0f
        env.addPassthroughOpacityChangedListener { opacity -> diagnostics.passthrough = opacity }
    }
}
```

Runtime permission request (standard Android; constants live in `androidx.xr.runtime.manifest.ManifestPermission`):

```kotlin
val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
    ManifestPermission.SCENE_UNDERSTANDING_COARSE, ManifestPermission.SCENE_UNDERSTANDING_FINE,
    ManifestPermission.HAND_TRACKING, ManifestPermission.HEAD_TRACKING)
registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants -> /* speak result, then configure() */ }.launch(perms)
```
Since runtime 1.0.0-alpha05, `Session.create`/`configure` throw `SecurityException` (no `PermissionsNotGranted` result).

### R2 — ExecuTorch `Module` + `LlmModule` (tag v1.3.1)

Sources (all at tag v1.3.1 unless noted):
Module https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/extension/android/executorch_android/src/main/java/org/pytorch/executorch/Module.java ;
Tensor …/Tensor.java ; EValue …/EValue.java ; ExecuTorchRuntime …/ExecuTorchRuntime.java ;
LlmModule https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/extension/android/executorch_android/src/main/java/org/pytorch/executorch/extension/llm/LlmModule.java ;
LlmModuleConfig …/extension/llm/LlmModuleConfig.java (SYN) ; LlmGenerationConfig …/extension/llm/LlmGenerationConfig.java (SYN) ;
LlmCallback …/extension/llm/LlmCallback.java ; Android LLM doc https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/docs/source/llm/run-on-android.md ;
yolo26 https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/examples/models/yolo26/export_and_validate.py ;
gemma4 https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/examples/models/gemma4/export_gemma4.py ;
multimodal runner https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/extension/llm/runner/multimodal_prefiller.cpp ;
backend key https://raw.githubusercontent.com/pytorch/executorch/v1.3.1/backends/xnnpack/runtime/XNNPACKBackend.h .

**`org.pytorch.executorch.Module`** (`@Experimental`, implements `Closeable`) — ET-PS, ET-AC:
```
public static Module load(String modelPath)                         // = load(path, LOAD_MODE_FILE)
public static Module load(String modelPath, int loadMode)           // numThreads 0 = hardware default
public static Module load(String modelPath, int loadMode, int numThreads)
public EValue[] forward(EValue... inputs)                           // = execute("forward", inputs)
public EValue[] execute(String methodName, EValue... inputs)
public void loadMethod(String methodName)
public String[] getMethods()
public MethodMetadata getMethodMetadata(String name)                // .getBackends(): String[]; populated at load, no loadMethod needed
public String[] readLogBuffer();  public static String[] readLogBufferStatic()
public boolean etdump();  public boolean etdump(String outputPath)
public void destroy();  public void close()
LOAD_MODE_FILE = 0, LOAD_MODE_MMAP = 1, LOAD_MODE_MMAP_USE_MLOCK = 2, LOAD_MODE_MMAP_USE_MLOCK_IGNORE_ERRORS = 3
```
**`Tensor` / `EValue`** — ET-PS, ET-AC: `Tensor.fromBlob(float[] data, long[] shape)` (copies into a direct
native-order buffer); `fromBlob(FloatBuffer, long[])` (direct + nativeOrder, aliased, NOT copied);
`fromBlob(byte[]|int[]|short[]|long[]|double[], long[])`; `fromBlobUnsigned(byte[], long[])`;
`Tensor.allocateFloatBuffer(int)`; `long[] shape()`; `long numel()`; `DType dtype()`;
`float[] getDataAsFloatArray()`; `copyDataInto(FloatBuffer)`. `EValue.from(Tensor|boolean|long|double|String)`;
`toTensor()`; `isTensor()`. **No `EValue.from(int)`**: a literal widens, an `Int` variable needs `.toLong()`.

**Paths** — ET-PS, ET-AC: `Module.load` and every `LlmModule` constructor call
`ExecuTorchRuntime.validateFilePath` → `IllegalArgumentException("Cannot load model path: path does not exist|is not a file|is not readable: …")`.
Assets and content URIs are not loadable; copy to `filesDir` (small models) or `adb push` to
`/sdcard/Android/data/com.vysion.xr/files/` (multi-GB VLM; readable via `getExternalFilesDir(null)` without any permission)
and use `LOAD_MODE_MMAP`. The main-branch doc has the copy-to-filesDir sample; the v1.3.1 doc still shows `/data/local/tmp`, which fails validation.

**Backend name for the overlay** — ET-PS, ET-AC: `module.getMethodMetadata("forward").getBackends()` returns
`["XnnpackBackend"]` for an XNNPACK-lowered method (`xnnpack_backend_key[] = "XnnpackBackend"`);
non-delegated ops run on portable/optimized kernels and do not appear. `ExecuTorchRuntime.getRegisteredBackends()`
lists compiled-in backends, not what the model uses. `LlmModule` has no metadata API: open the same .pte once with
`Module.load(path, LOAD_MODE_MMAP)`, read `getMethods()`/`getBackends()`, `close()`.

Detector path:
```kotlin
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

fun copyAssetOnce(ctx: Context, name: String): String {
    val f = File(ctx.filesDir, name)
    if (!f.exists()) ctx.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
    return f.absolutePath
}
val det = Module.load(copyAssetOnce(ctx, "yolo26n_int8_xnnpack.pte"), Module.LOAD_MODE_MMAP, /*numThreads=*/4)
val backend = det.getMethodMetadata("forward").getBackends().joinToString()      // "XnnpackBackend"
// preprocess: letterbox 640x640 (pick ONE pad value and keep the un-pad math consistent), RGB, /255, CHW float32
val input = Tensor.fromBlob(chw, longArrayOf(1, 3, 640, 640))
val t0 = SystemClock.elapsedRealtimeNanos()
val out = det.forward(EValue.from(input))[0].toTensor()                           // [1, N, 6], post-NMS
val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
val d = out.getDataAsFloatArray(); val n = out.shape()[1].toInt()
for (i in 0 until n) { val o = i * 6 /* x1=d[o] y1=d[o+1] x2=d[o+2] y2=d[o+3] conf=d[o+4] cls=d[o+5].toInt() */ }
det.close()   // SRS: unload before the VLM runs, re-create after
```
YOLO26 facts — ET-PS, ET-AC: `examples/models/yolo26` exists at v1.3.1 (`yolo12` is an empty stub);
`--model_name {yolo26n,yolo26s,yolo26m,yolo26l,yolo26x}` default yolo26s; `--backend {openvino,xnnpack}` default
openvino; `--input_dims=[640,640]`; `--quantize` = XNNPACK static int8 (symmetric, per-tensor) and **requires**
`--video_path` (300 calibration frames by default); output file name is fixed `<model>_{int8|fp32}_{backend}.pte` in CWD;
input float32 NCHW [1,3,640,640] RGB letterboxed /255; output `[1, N, 6]` rows `[x1,y1,x2,y2,confidence,class_id]`
in letterboxed-input pixels (`inference.h` lines 90-108); COCO80 ids (`person`=0, chair=56, couch=57, …).
```bash
cd examples/models/yolo26 && python -m pip install --upgrade-strategy only-if-needed --extra-index-url https://download.pytorch.org/whl/cpu -r requirements.txt   # ultralytics==8.4.14, opencv-python
python export_and_validate.py --model_name yolo26n --backend xnnpack --quantize --video_path calib.mp4 --input_dims=[640,640]   # -> ./yolo26n_int8_xnnpack.pte
```

**`org.pytorch.executorch.extension.llm.LlmModule`** — ET-PS, ET-AC (+ SYN for the config classes):
```
MODEL_TYPE_TEXT = 1; MODEL_TYPE_TEXT_VISION = 2; MODEL_TYPE_MULTIMODAL = 2   (2 -> llm::create_multimodal_runner)
public LlmModule(String modulePath, String tokenizerPath, float temperature)
public LlmModule(int modelType, String modulePath, String tokenizerPath, float temperature)
public LlmModule(int modelType, String modulePath, String tokenizerPath, float temperature, String dataPath)
public LlmModule(int modelType, String modulePath, String tokenizerPath, float temperature, List<String> dataFiles, int numBos, int numEos)
public LlmModule(LlmModuleConfig config)   // <- use this
LlmModuleConfig.create().modulePath(String).tokenizerPath(String).temperature(float).dataPath(String).modelType(int).numBos(int).numEos(int).loadMode(int).build()
  Builder defaults: temperature 0.8f, modelType TEXT, loadMode MMAP.  Constants also on LlmModuleConfig: LOAD_MODE_* and MODEL_TYPE_*.
public void generate(String prompt, LlmCallback cb)                                   // DEFAULT_SEQ_LEN 128, DEFAULT_ECHO true
public void generate(String prompt, int seqLen, LlmCallback cb)
public void generate(String prompt, LlmCallback cb, boolean echo)
public void generate(String prompt, int seqLen, LlmCallback cb, boolean echo)
public void generate(String prompt, int seqLen, LlmCallback cb, boolean echo, float temperature, int numBos, int numEos)
public void generate(String prompt, LlmGenerationConfig config, LlmCallback cb)       // forwards ONLY seqLen/echo/temperature/numBos/numEos; maxNewTokens is DROPPED
public void generate(int[] image, int width, int height, int channels, String prompt, int seqLen, LlmCallback cb, boolean echo)
@Experimental public void prefillImages(int[] image /*uint8 0..255, CHW*/, int width, int height, int channels)
@Experimental public void prefillImages(ByteBuffer image /*direct, uint8 CHW*/, int width, int height, int channels)
@Experimental public void prefillImages(float[] image /*already normalized, CHW*/, int width, int height, int channels)
@Experimental public void prefillNormalizedImage(ByteBuffer image /*direct, float CHW, nativeOrder, 4-byte aligned*/, int width, int height, int channels)
@Experimental public void prefillPrompt(String prompt)
public void resetContext();  public void stop();  public void load();  public void close()
interface LlmCallback { void onResult(String result); default void onStats(String statsJson) {}; default void onError(int errorCode, String message) {} }
LlmGenerationConfig.create().echo(boolean).maxNewTokens(int).warming(boolean).seqLen(int).temperature(float).numBos(int).numEos(int).build()
```
Semantics that bite (ET-PS, ET-AC): there is NO `generateFromPos`; `generate()` BLOCKS (run on `Dispatchers.Default`,
never on Main); calling any LlmModule method from inside a callback throws
`IllegalStateException("Cannot call LlmModule methods from within a callback")`; `stop()` is lock-free and safe
from another thread (FR-G4); `close()` throws if `generate()` is in flight. `seqLen` is an ABSOLUTE position budget:
`max_new = min(seqLen, max_context_len) - pos_` where `pos_` counts every prefilled token incl. image soft tokens
and prior turns, and must stay > 0 — so the 128 default errors after a 140-token image and each follow-up on the
same KV cache needs a larger seqLen; `maxNewTokens` is never forwarded in 1.3.1. Constructor `temperature` has no
fallback (negative inverts the logits): pass `0f` for greedy read-verbatim; the generate-time `-1f` means "use the
constructor value". `numBos` passed to `generate()` is ignored (JNI uses the constructor's, re-armed by
`resetContext()`). `prefillImages(int[])` truncates each int to uint8. `onStats` JSON keys:
`prompt_tokens, generated_tokens, model_load_start_ms, model_load_end_ms, inference_start_ms, inference_end_ms,
prompt_eval_end_ms, first_token_ms, aggregate_sampling_time_ms, SCALING_FACTOR_UNITS_PER_SECOND` (+
`prefill_token_per_sec`, `decode_token_per_sec` when non-zero); TTFT = `first_token_ms - inference_start_ms`.

VLM path (valid ONLY for an LlmModule-compatible .pte, i.e. option C below):
```kotlin
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import org.pytorch.executorch.extension.llm.LlmModuleConfig

val vlm = LlmModule(LlmModuleConfig.create()
    .modulePath(ptePath).tokenizerPath(tokJsonPath)
    .modelType(LlmModuleConfig.MODEL_TYPE_MULTIMODAL).temperature(0f).loadMode(LlmModuleConfig.LOAD_MODE_MMAP).build())
vlm.load()                                              // eager; else first generate() pays it
vlm.resetContext()
vlm.prefillImages(rgbChwUint8 /* IntArray 0..255 */, w, h, 3)   // or prefillNormalizedImage(directFloatBuf, w, h, 3)
val sb = StringBuilder()
vlm.generate(PromptLibrary.READ, /*seqLen=*/ 512, object : LlmCallback {
    override fun onResult(result: String) { sb.append(result) }          // stream sentence-by-sentence into TTS
    override fun onStats(stats: String) { /* parse JSON above */ }
    override fun onError(errorCode: Int, message: String) { }
}, /*echo=*/ false)
// follow-up on the same frame (FR-D2): vlm.generate(followUp, seqLen + more, cb, false)  -- KV cache keeps the image
// "stop": vlm.stop() from any thread; generate() returns; only then close()
```

**D5 — the Gemma 4 decision R2 must make before Sat 11:00** (ET-PS, ET-AC). `examples/models/gemma4` exists at v1.3.1;
`export_gemma4.py` produces ONE .pte with methods `speech_transform, audio_encoder, vision_encoder, text_decoder`
(`text_decoder(input_ids:int64[1,S], input_pos:int64[S], inputs_embeds:float[1,S,1536])`); flags: `--checkpoint_path`
(required), `--variant {e2b,e4b}`, `--quantize {8da4w+emb8,8da4w+emb4,8da8w+emb8,none}` (default 8da4w+emb8),
`--vision_quantize` (8da8w), `--audio_quantize`, `--max_seq_len 1024`, `--max_patches 1260` (= 140 soft tokens; its
help text wrongly says 2520), `--group_size 128`, `--no-audio`, `--no-vision`, `--tied_embedding` (DON'T: needs C++
TorchAO kernels), `--use_custom_sdpa`. Needs `transformers>=5.2.0` (repo pins 5.0.0rc1 → install separately) and the
ungated `google/gemma-4-E2B-it` (ships `tokenizer.json`, no `tokenizer.model`). README S25 numbers (C++ runner):
4.3 GB file, load 0.8 s, image encode 2.73 s, TTFT 3.83 s, 6 tok/s, 10.14 s for ~2 sentences, peak 2600 MB.
```bash
python examples/models/gemma4/export_gemma4.py --checkpoint_path ./gemma-4-E2B-it --variant e2b --no-audio \
  --quantize 8da4w+emb8 --vision_quantize 8da8w --max_seq_len 1024 --max_patches 1260 --output_path gemma4_e2b_vision.pte
```
But `LlmModule(MODEL_TYPE_MULTIMODAL, "gemma4.pte", "tokenizer.json", 0f)` fails in `MultimodalPrefiller::load()`
(no `token_embedding` method; `Error::InvalidArgument` = 18 → `ExecutorchRuntimeException("Failed to load model…")`
or `onError(18, …)`). `jni_layer_llama.cpp` has zero gemma4 code at v1.3.1, v1.5.1 and main. Options:

| Option | What | Cost / risk |
|---|---|---|
| **C. Swap VLM to Gemma 3 4B** | `google/gemma-3-4b-it` via optimum-executorch 1.1.0 (`--task multimodal-text-to-text --recipe xnnpack --qlinear 8da4w --qlinear_group_size 32 --qlinear_encoder 8da4w,8da8w --qlinear_encoder_group_size 32 --qembedding 8w --qembedding_encoder 8w --use_custom_sdpa --use_custom_kv_cache`; this exact config is optimum-executorch's `test_gemma3_image_vision_with_custom_sdpa_kv_cache_8da4w_8we`) → `model.pte` + `tokenizer.json` with `token_embedding/text_decoder/vision_encoder`; load with the snippet above | ~2 h; HF model is **gated** (accept license + token); separate venv (pins transformers 5.0.0rc1); ~4B params → memory near the 4 GB budget; image size model-specific (SigLIP 896×896 / 256 tokens **UNVERIFIED**, HTTP 401) |
| **A. Kotlin port of `Gemma4Runner` over plain `Module`** | `module.execute("vision_encoder", pixel_values[1,max_patches,768] float32 [0,1], pixel_position_ids[1,max_patches,2] int64 -1-padded)` then a decode loop over `execute("text_decoder", input_ids, input_pos, inputs_embeds)`; constants `kBosId=2, kEosId=1, kTurnStartId=105, kTurnEndId=106, kBoiTokenId=255999, kImageTokenId=258880, kEoiTokenId=258882`; patch grid: sides multiples of 48 (`kPatchSize 16 × kPoolingKernel 3`), `num_tokens = num_patches / 9`; KV reset = restart `input_pos` at 0 (no Module reload; C++ `reset()` is effectively a no-op) | ~1 day; **no tokenizer class in the AAR** → pre-tokenize the four fixed prompts offline, decode ids via `tokenizer.json` vocab (`▁`→space, `<0xNN>` bytes); follow-up questions need real encoding |
| B. From-source AAR + custom JNI around `Gemma4Runner` | `scripts/build_android_library.sh` + `jni_layer_gemma4.cpp` | highest schedule risk |

SRS fallback "SmolVLM-500M via the Qualcomm VLM example" has no counterpart at v1.3.1 (`examples/models/smolvlm`
has only a config + `convert_weights.py`; `examples/qualcomm/oss_scripts` has no smolvlm). `supertonic` TTS exists
only on main/v1.5.1; `voxtral_tts` exists at v1.3.1 (P2).

Export environment: `python3.12 -m venv .venv && pip install "executorch==1.3.1"`; clone the repo at tag v1.3.1
for `examples/` (the wheel ships `executorch.examples.models.*` via a symlink, so keep script and package at the
same revision); a .pte built with 1.3.1 runs on the 1.3.1 AAR — do not mix versions.

Overlay fields (FR-G5): backend from `getBackends()`; model name/quant = string constants next to the file;
VLM ms from `onStats` or wall clock; detector fps = counter around `forward()`; memory = PSS (R5);
runtime log = `Module.readLogBufferStatic()`. Hard-code `"executorch-android 1.3.1"` next to measured values
(the AAR exposes no version API).

#### R2 · Cloud Describe (ADR-0010 row): Gemini REST, no new dependency

`inference/GeminiCloudDescriber.kt` calls the Gemini Developer API with the platform HTTP client (no SDK, nothing added to
`libs.versions.toml`); the connecting line carries `network-fallback:` (`check_privacy.sh` §6). Fallback: the
on-device path below (`CloudDescribeEngine`, hedge at 1 s).

```
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:streamGenerateContent?alt=sse
x-goog-api-key: <GEMINI_API_KEY from local.properties, via BuildConfig>; X-Android-Package / X-Android-Cert      // header, never ?key= in the URL
{"systemInstruction":{"parts":[{"text":PromptLibrary.CLOUD_SPOKEN_STYLE}]},
 "contents":[{"role":"user","parts":[{"inlineData":{"mimeType":"image/jpeg","data":<base64>}},{"text":<prompt>}]}],
 "generationConfig":{"maxOutputTokens":400,"thinkingConfig":{"thinkingLevel":"minimal"}}}
→ server-sent events, one `data: {GenerateContentResponse}` per chunk; text = candidates[0].content.parts[].text
```

Sources, read 2026-10-18: model ids https://ai.google.dev/gemini-api/docs/models (`gemini-3.6-flash` stable;
`gemini-3.8-flash` newest stable, but its lowest thinking level is `low`); thinking levels
https://ai.google.dev/gemini-api/docs/thinking (`minimal` allowed for 3.6 Flash and 3.5 Flash-Lite; thought tokens count
against `maxOutputTokens`); streaming https://ai.google.dev/api/generate-content. Verified 2026-10-18: the key
lists `models/gemini-3.6-flash`, `thinkingLevel: "minimal"` is accepted, and the stream is one `data:` line per chunk
(the last chunk can carry only a `thoughtSignature` with empty text). On the headset: `docs/benchmarks.md` "Cloud Describe".

### R3 — ARCore for Jetpack XR session, config, planes, depth, anchors, hands, head + SceneCore spatial audio

Sources: API files (frozen at beta01; rc01.txt is byte-identical, rc02 is bug-fix only, verified by javap on rc02 jars)
https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/xr/runtime/runtime/api/1.0.0-rc01.txt ,
https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/xr/arcore/arcore/api/1.0.0-rc01.txt ,
https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/xr/scenecore/scenecore/api/1.0.0-rc01.txt ;
Session.kt https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/xr/runtime/runtime/src/main/kotlin/androidx/xr/runtime/Session.kt ;
guides https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore ( /arcore/planes , /arcore/depth , /arcore/hands , /arcore/anchors , /arcore/device-pose ),
https://developer.android.com/develop/xr/jetpack-xr-sdk/add-spatial-audio ,
release notes https://developer.android.com/jetpack/androidx/releases/xr-arcore , …/xr-scenecore , …/xr-runtime .

| Need | Exact API (androidx.xr.runtime / androidx.xr.arcore / androidx.xr.scenecore) | Src |
|---|---|---|
| Create | `suspend fun Session.Companion.create(context: Context, coroutineContext: CoroutineContext = EmptyCoroutineContext, lifecycleOwner: LifecycleOwner = context as LifecycleOwner): SessionCreateResult` — call with **named** `context =` (D7), off the main thread; do NOT pass `applicationContext` (ClassCastException) | XR-PS, XR-AC |
| Create results | sealed nonexhaustive: `SessionCreateSuccess(session)`, `SessionCreateUnsupportedDevice`, `SessionCreateApkRequired(requiredApk)`, `SessionCreateTimedOut`, `SessionCreateUnknownError(errorMessage)`; no PermissionsNotGranted (SecurityException instead) → keep `else ->` | XR-PS, XR-AC |
| Configure | `fun Session.configure(config: Config): SessionConfigureResult` → `SessionConfigureSuccess`, `SessionConfigureCalibrationRequired(calibrationType)`, `SessionConfigureLibraryNotLinked(libraryName)`, `SessionConfigureUnknownError(errorMessage)`; throws `SecurityException` / `UnsupportedOperationException` | XR-PS, XR-AC |
| Config | `Config.Builder(session.config).setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL).setDepthEstimation(DepthEstimationMode.SMOOTH_ONLY).setHandTracking(HandTrackingMode.BOTH).setDeviceTracking(DeviceTrackingMode.SPATIAL).setAnchorPersistence(AnchorPersistenceMode.LOCAL).build()`; modes: PlaneTrackingMode {DISABLED, HORIZONTAL_AND_VERTICAL}, HandTrackingMode {DISABLED, BOTH}, DepthEstimationMode {DISABLED, RAW_ONLY, SMOOTH_ONLY, SMOOTH_AND_RAW}, DeviceTrackingMode {DISABLED, SPATIAL} (+experimental INERTIAL), AnchorPersistenceMode {DISABLED, LOCAL}. **No `headTracking` field**; head = `deviceTracking = SPATIAL` + `HEAD_TRACKING` permission. No public `Config(...)` constructor — use the Builder | XR-PS, XR-AC |
| Capability probe (no session) | `XrDevice.getCurrentDevice(context).isDepthEstimationModeSupported(mode)`, `.isHandTrackingModeSupported(mode)`, `.isRenderingModeSupported(RenderingMode.STEREO)` | XR-PS, XR-AC |
| Planes | `Plane.subscribe(session): StateFlow<Collection<Plane>>`; `plane.state.value` → `trackingState: TrackingState {TRACKING, PAUSED, STOPPED}`, `label: PlaneLabel {UNKNOWN, WALL, FLOOR, CEILING, TABLE}`, `centerPose: Pose`, `extents: FloatSize2d` (m), `vertices: List<Vector2>`; `plane.type: PlaneType {HORIZONTAL_UPWARD_FACING, HORIZONTAL_DOWNWARD_FACING, VERTICAL}`; `plane.createAnchor(pose): AnchorResult` | XR-PS, XR-AC |
| Depth | class is **`Depth`** (not DepthMap): `Depth.left(session)` / `right` / `mono`; `state: StateFlow<Depth.State>` → `width: Int`, `height: Int`, `smoothDepthMap: FloatBuffer?` (meters), `rawDepthMap: FloatBuffer?`, `smoothConfidenceMap: ByteBuffer?` (0..255), `rawConfidenceMap: ByteBuffer?`; accessing an unsupported map throws IllegalStateException; choose left/right vs mono via `isRenderingModeSupported(RenderingMode.STEREO)` | XR-PS, XR-AC |
| Hands | `Hand.left(session)` / `Hand.right(session)`; `state: StateFlow<Hand.State>` → `handJoints: Map<HandJointType, Pose>`, `trackingState`, `handJointsBuffer: FloatBuffer`; `HandJointType` = 26 OpenXR joints (`PALM, WRIST, THUMB_{METACARPAL,PROXIMAL,DISTAL,TIP}, {INDEX,MIDDLE,RING,LITTLE}_{METACARPAL,PROXIMAL,INTERMEDIATE,DISTAL,TIP}`); `Hand.getPrimaryHandSide(contentResolver): HandSide {LEFT, RIGHT, UNKNOWN}`; pinch per docs: `Vector3.distance(thumbTip.translation, indexTip.translation) < 0.05` after `session.scene.perceptionSpace.transformPoseTo(pose, session.scene.activitySpace)` | XR-PS, XR-AC |
| Anchors | `Anchor.create(session, pose): AnchorResult` → `AnchorCreateSuccess(anchor)` / `AnchorCreateResourcesExhausted` / `AnchorCreateTrackingUnavailable` (sealed nonexhaustive); `anchor.state.value.pose / .trackingState`; `anchor.detach()`; **`suspend fun persist(): UUID`**; `Anchor.load(session, uuid)`, `Anchor.getPersistedAnchorUuids(session)`, `Anchor.unpersist(session, uuid)`. `HitResult.createAnchor` was removed in alpha15 → `(hit.trackable as? Plane)?.createAnchor(hit.hitPose)` or `Anchor.create(session, hit.hitPose)` | XR-PS, XR-AC |
| Hit test | top-level `androidx.xr.arcore.hitTest(session, Ray(origin, direction)): List<HitResult>` (Java facade `Interaction.hitTest`); `HitResult(distance: Float, hitPose: Pose, trackable)`; `Ray`/`Pose`/`Vector3` in `androidx.xr.runtime.math` | XR-PS, XR-AC |
| Head pose | `ArDevice.getInstance(session).state.value.devicePose` (needs `DeviceTrackingMode.SPATIAL` + `HEAD_TRACKING`); per-eye `RenderViewpoint.left/right/mono(session).state.value.pose / localPose / fieldOfView`; SceneCore's `SpatialUser`/`Head`/`CameraView` were REMOVED in scenecore alpha11; `session.scene.perceptionSpace.getScenePoseFromPerceptionPose(pose)` bridges to SceneCore | XR-PS, XR-AC |
| Spatial audio | `SpatialSoundPool.play(session, soundPool, soundID: Int, params: PointSourceParams, entity: Entity, volume: Float, priority: Int, loop: Int, rate: Float): Int` (defaults exist for the last four but their values are not recoverable from bytecode → pass all four); `PointSourceParams()` no-arg (entity moved to the call in alpha14); `SpatialMediaPlayer.setPointSourceParams(session, mediaPlayer, params, entity)`; `SpatialAudioTrack.setPointSourceParams(session, track, params, entity)`; `SoundFieldAttributes(SpatializerConstants.AmbisonicsOrder.FIRST_ORDER)`; gate on `SpatialCapability.SPATIAL_AUDIO`; files mono/stereo; AudioAttributes must NOT be `USAGE_MEDIA` nor `CONTENT_TYPE_MUSIC/MOVIE/SPEECH` | XR-PS, XR-AC |
| Entities | `AnchorSpace.create(session, anchor: androidx.xr.arcore.Anchor)` (renamed from AnchorEntity in beta01); `Entity.create(session, name: String, pose: Pose, parent: Entity)` with 1–4-arg overloads; `AnchorEntity`, `GroupEntity`, `ContentlessEntity` are absent from rc02 | XR-PS, XR-AC |

```kotlin
import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.Depth
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.arcore.HandSide
import androidx.xr.arcore.Plane
import androidx.xr.arcore.PlaneLabel
import androidx.xr.arcore.TrackingState
import androidx.xr.arcore.hitTest
import androidx.xr.runtime.AnchorPersistenceMode
import androidx.xr.runtime.Config
import androidx.xr.runtime.DepthEstimationMode
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.PlaneTrackingMode
import androidx.xr.runtime.RenderingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.XrDevice
import androidx.xr.runtime.math.Ray
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.PointSourceParams
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.SpatialSoundPool
import androidx.xr.scenecore.scene

// 1. Session (D7): named arg, off the main thread
lifecycleScope.launch {
    val r = withContext(Dispatchers.IO) { Session.create(context = this@MainActivity) }
    when (r) { is SessionCreateSuccess -> onSession(r.session); else -> speaker.error("XR session unavailable") }
}
fun configurePerception(session: Session) {
    val cfg = Config.Builder(session.config)
        .setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL)   // SCENE_UNDERSTANDING_COARSE
        .setDepthEstimation(DepthEstimationMode.SMOOTH_ONLY)           // SCENE_UNDERSTANDING_FINE
        .setHandTracking(HandTrackingMode.BOTH)                        // HAND_TRACKING
        .setDeviceTracking(DeviceTrackingMode.SPATIAL)                 // HEAD_TRACKING
        .setAnchorPersistence(AnchorPersistenceMode.LOCAL)
        .build()
    try { when (session.configure(cfg)) { is SessionConfigureSuccess -> {}; else -> {} } }
    catch (e: SecurityException) { /* permission missing */ } catch (e: UnsupportedOperationException) { /* mode unsupported */ }
}
// 2. Planes -> "wall ahead" / "floor ends"
Plane.subscribe(session).collect { planes -> planes.forEach { p ->
    val s = p.state.value
    if (s.trackingState == TrackingState.TRACKING && s.label == PlaneLabel.WALL) { s.centerPose; s.extents }
} }
// 3. Depth -> proximity cone (meters)
val stereo = XrDevice.getCurrentDevice(context).isRenderingModeSupported(RenderingMode.STEREO)
val depth = if (stereo) Depth.left(session) else Depth.mono(session)
depth.state.collect { d -> val buf = d.smoothDepthMap ?: return@collect /* d.width * d.height floats */ }
// 4. Hands -> pinch-and-hold push-to-talk
val hand = if (Hand.getPrimaryHandSide(contentResolver) == HandSide.LEFT) Hand.right(session) else Hand.left(session)
hand.state.collect { hs ->
    if (hs.trackingState != TrackingState.TRACKING) return@collect
    val thumb = hs.handJoints[HandJointType.THUMB_TIP] ?: return@collect
    val index = hs.handJoints[HandJointType.INDEX_TIP] ?: return@collect
    val ps = session.scene.perceptionSpace; val asp = session.scene.activitySpace
    val pinching = Vector3.distance(ps.transformPoseTo(thumb, asp).translation, ps.transformPoseTo(index, asp).translation) < 0.05f
}
// 5. Head pose + hit test + anchor (door)
val head = ArDevice.getInstance(session).state.value.devicePose
val hits = hitTest(session, Ray(origin = head.translation, direction = head.forward))
val door = hits.firstOrNull { (it.trackable as? Plane)?.state?.value?.label == PlaneLabel.WALL }
when (val a = Anchor.create(session, door?.hitPose ?: return)) {
    is AnchorCreateSuccess -> anchorStore.door = a.anchor      // later: a.anchor.state.value.pose; suspend a.anchor.persist()
    else -> speaker.say(Cues.NO_DOOR)                           // "I can't find a door, turn slowly"
}
// 6. Spatial ping at the anchor (SceneCore)
val anchorSpace = AnchorSpace.create(session, anchor).apply { parent = session.scene.activitySpace }
val pingEntity: Entity = Entity.create(session, "doorPing")
anchorSpace.addChild(pingEntity)
if (session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO)) {
    val pool = SoundPool.Builder().setAudioAttributes(AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build()
    val id = pool.load(assets.openFd("sounds/ping.ogg"), 0)
    pool.setOnLoadCompleteListener { sp, _, status -> if (status == 0)
        SpatialSoundPool.play(session = session, soundPool = sp, soundID = id, params = PointSourceParams(),
            entity = pingEntity, volume = 1f, priority = 0, loop = 0, rate = 1f) }   // every 3 s from NavigationEngine
}
```
Device applicability: the planes/depth guides list "XR Headsets" and the Aug 18 2026 beta blog says to test on
Galaxy XR, but no per-device matrix exists; treat `SessionConfigureSuccess` as the proof
(https://android-developers.googleblog.com/2026/08/jetpack-xr-sdk-core-libraries-beta.html).

### R4 — `SpeechRecognizer` / `TextToSpeech`

Sources: https://developer.android.com/reference/android/speech/SpeechRecognizer (page updated 2026-08-28),
https://developer.android.com/reference/android/speech/RecognizerIntent ,
https://developer.android.com/reference/android/speech/ModelDownloadListener ,
https://developer.android.com/reference/android/speech/tts/TextToSpeech ,
https://developer.android.com/reference/android/speech/tts/TextToSpeech.Engine ,
https://developer.android.com/reference/android/speech/tts/Voice ,
https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener ,
XR ASR guide (glasses-scoped) https://developer.android.com/develop/xr/jetpack-xr-sdk/asr . All DV-PS + DV-AC.

| Fact | Exact |
|---|---|
| Factories | `static SpeechRecognizer createOnDeviceSpeechRecognizer(Context)` (API 31; throws `UnsupportedOperationException` iff `isOnDeviceRecognitionAvailable(Context)` (API 31) is false); `createSpeechRecognizer(Context)` (API 8, gate on `isRecognitionAvailable(Context)`; needs the manifest `<queries>` in 2.7). **All methods on the main thread; `destroy()` mandatory; `RECORD_AUDIO` required.** |
| Support / download | `checkRecognitionSupport(Intent, Executor, RecognitionSupportCallback)` (API 33) → `RecognitionSupport.getInstalledOnDeviceLanguages() / getSupportedOnDeviceLanguages() / getPendingOnDeviceLanguages() / getOnlineLanguages(): List<String>`; `triggerModelDownload(Intent, Executor, ModelDownloadListener)` (API 34; `onSuccess()` is called directly if already installed; `onProgress` may never fire; `onScheduled()` = no further callbacks) |
| Intent | `RecognizerIntent.ACTION_RECOGNIZE_SPEECH = "android.speech.action.RECOGNIZE_SPEECH"`; `EXTRA_LANGUAGE_MODEL = "android.speech.extra.LANGUAGE_MODEL"` (required) with `LANGUAGE_MODEL_FREE_FORM = "free_form"`; `EXTRA_LANGUAGE = "android.speech.extra.LANGUAGE"` (BCP 47, `"es-MX"`, `"en-US"`); `EXTRA_PREFER_OFFLINE = "android.speech.extra.PREFER_OFFLINE"` (boolean, API 23, "may have no effect"); `EXTRA_PARTIAL_RESULTS = "android.speech.extra.PARTIAL_RESULTS"` |
| Results | `SpeechRecognizer.RESULTS_RECOGNITION = "results_recognition"` → `ArrayList<String>` (first = most likely); `CONFIDENCE_SCORES = "confidence_scores"` → `float[]` 0..1 or -1, **optional (null-check)** |
| Errors | `ERROR_NETWORK_TIMEOUT=1, ERROR_NETWORK=2, ERROR_AUDIO=3, ERROR_SERVER=4, ERROR_CLIENT=5, ERROR_SPEECH_TIMEOUT=6, ERROR_NO_MATCH=7, ERROR_RECOGNIZER_BUSY=8, ERROR_INSUFFICIENT_PERMISSIONS=9, ERROR_TOO_MANY_REQUESTS=10, ERROR_SERVER_DISCONNECTED=11, ERROR_LANGUAGE_NOT_SUPPORTED=12, ERROR_LANGUAGE_UNAVAILABLE=13, ERROR_CANNOT_CHECK_SUPPORT=14, ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS=15` |
| XR doc | ASR guide says SpeechRecognizer "works even when offline" but its applicable-devices box lists only "Audio & Display Glasses"; nothing from Google or Samsung confirms on-device ASR or an offline es-MX pack on Galaxy XR → runtime check + overlay line |
| TTS core | `TextToSpeech(Context, OnInitListener)`; `setLanguage(Locale): Int` → `LANG_AVAILABLE=0, LANG_COUNTRY_AVAILABLE=1, LANG_COUNTRY_VAR_AVAILABLE=2, LANG_MISSING_DATA=-1, LANG_NOT_SUPPORTED=-2`; `speak(CharSequence, Int queueMode, Bundle?, String utteranceId): Int` (API 21, async, returns queuing SUCCESS=0/ERROR=-1); `QUEUE_FLUSH=0` (flushes this app's entries only), `QUEUE_ADD=1`; `stop()`; `setSpeechRate(Float)` (1.0 normal, 0.5 half → 0.7f for "slower"); `setPitch`; `shutdown()`; `getMaxSpeechInputLength()` (API 18); errors `ERROR_SYNTHESIS=-3, ERROR_SERVICE=-4, ERROR_OUTPUT=-5, ERROR_NETWORK=-6, ERROR_NETWORK_TIMEOUT=-7, ERROR_INVALID_REQUEST=-8, ERROR_NOT_INSTALLED_YET=-9, STOPPED=-2`. Class not deprecated |
| Offline voice | `getVoices(): Set<Voice>` (API 21; **null before init**); `setVoice(Voice)`; `Voice.getLocale()`, `isNetworkConnectionRequired()`, `getQuality()` (VERY_LOW 100 … VERY_HIGH 500, higher better), `getLatency()` (100 … 500, lower better), `getFeatures(): Set<String>` (**may be null**); skip voices whose features contain `TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED` (= `"notInstalled"`); this replaces the deprecated `KEY_FEATURE_EMBEDDED_SYNTHESIS` path |
| Progress | `UtteranceProgressListener` (abstract): `onStart(String)`, `onDone(String)`, `onError(String)` (abstract, deprecated 21 — still must override), `onError(String, Int)` (21), `onStop(String, Boolean interrupted)` (23, fires on `stop()`/QUEUE_FLUSH), `onRangeStart(String, Int start, Int end, Int frame)` (26, only if the engine supplies timing); callbacks "can be called from multiple threads"; `Engine.KEY_PARAM_UTTERANCE_ID = "utteranceId"` |
| Earcons | `addEarcon(String, String packageName, Int resId)`; `playEarcon(String, Int queueMode, Bundle?, String utteranceId)` (21); `playSilentUtterance(Long ms, Int queueMode, String id)` (21) |

```kotlin
// CommandBus: construct ON THE MAIN THREAD (factories require it).
class CommandBus(private val ctx: Context) {
    val onDevice: Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    private val recognizer: SpeechRecognizer? = when {
        onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        SpeechRecognizer.isRecognitionAvailable(ctx) -> SpeechRecognizer.createSpeechRecognizer(ctx)   // may use network
        else -> null                                                                                     // pinch-to-talk + buttons only
    }
    private fun intent(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)                 // "en-US" / "es-MX"
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    fun checkSupport(tag: String, onResult: (RecognitionSupport) -> Unit) = recognizer?.checkRecognitionSupport(intent(tag), ctx.mainExecutor,
        object : RecognitionSupportCallback {
            override fun onSupportResult(recognitionSupport: RecognitionSupport) = onResult(recognitionSupport)
            override fun onError(error: Int) { }                       // ERROR_CANNOT_CHECK_SUPPORT = 14
        })
    fun downloadPack(tag: String) = recognizer?.triggerModelDownload(intent(tag), ctx.mainExecutor, object : ModelDownloadListener {
        override fun onProgress(completedPercent: Int) {}; override fun onSuccess() {}; override fun onScheduled() {}; override fun onError(error: Int) {}
    })                                                                 // needs network ONCE; run before airplane mode
    fun start(tag: String) { recognizer?.setRecognitionListener(object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val conf = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)   // may be null
        }
        override fun onPartialResults(partialResults: Bundle) {}
        override fun onError(error: Int) {}                            // 7 NO_MATCH, 6 SPEECH_TIMEOUT, 8 BUSY, 12/13 language
        override fun onReadyForSpeech(params: Bundle) {}               // play "listening" earcon
        override fun onBeginningOfSpeech() {}; override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray) {}; override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle) {}
    }); recognizer?.startListening(intent(tag)) }
    fun stop() = recognizer?.stopListening()
    fun close() = recognizer?.destroy()
}

class Speaker(ctx: Context) {
    @Volatile private var ready = false
    private val tts = TextToSpeech(ctx) { status -> ready = status == TextToSpeech.SUCCESS }
    /** Only after ready. Returns true only if an OFFLINE voice for the locale is installed. */
    fun useLocale(locale: Locale): Boolean {                           // Locale("es","MX"), Locale("es","ES"), Locale.US
        val code = tts.setLanguage(locale)
        if (code == TextToSpeech.LANG_MISSING_DATA || code == TextToSpeech.LANG_NOT_SUPPORTED) return false
        val voice = tts.voices.orEmpty()
            .filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired &&
                      it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .sortedWith(compareByDescending<Voice> { it.locale.country == locale.country }.thenByDescending { it.quality })
            .firstOrNull() ?: return false
        return tts.setVoice(voice) == TextToSpeech.SUCCESS
    }
    fun rate(r: Float) = tts.setSpeechRate(r)                          // 0.7f = "slower"
    fun say(text: String, id: String, interrupt: Boolean = true) =
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
    fun stopNow() = tts.stop()                                         // -> onStop(id, interrupted = true); FR-G4
    init { tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {}
        override fun onDone(utteranceId: String) {}
        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String) {}
        override fun onError(utteranceId: String, errorCode: Int) {}
        override fun onStop(utteranceId: String, interrupted: Boolean) {}
        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {}   // auto-scroll transcript
    }) }
    fun close() { tts.stop(); tts.shutdown() }
}
```
Contrast for the transcript panel (WCAG 2.2 SC 1.4.6, 7:1): `#FFFFFF` on `#000000` = 21.0:1; on `#121212` = 18.7:1
(https://www.w3.org/WAI/WCAG22/Understanding/contrast-enhanced.html). XR visual-design numbers: targets ≥ 56 dp,
icons ≥ 48 dp, text ≥ 14 dp, 0.868 dp-to-dmm, panels at 1.75 m
(https://developer.android.com/design/ui/xr/guides/visual-design). Compose semantics for TalkBack:
`Modifier.semantics { contentDescription = "…" }`, `Icon(contentDescription = …)`, `Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)`
(https://developer.android.com/develop/ui/compose/accessibility/semantics). No doc says TalkBack announces
`SpatialPanel` content (xr-compose alpha16 added `SubspaceModifier.semantics` with `contentDescription`) → test Sun 09:00.

### R5 — memory and permission introspection (diagnostics overlay)

Sources: https://developer.android.com/reference/android/os/Debug#getPss() ,
https://developer.android.com/reference/android/app/ActivityManager#getProcessMemoryInfo(int[]) ,
https://developer.android.com/reference/android/os/Debug.MemoryInfo , https://developer.android.com/reference/android/os/Debug ,
https://developer.android.com/reference/android/content/pm/PackageManager#getPackageInfo(java.lang.String,%20android.content.pm.PackageManager.PackageInfoFlags) ,
https://developer.android.com/reference/android/content/pm/PackageInfo#requestedPermissions . DV-PS + DV-AC.

| Fact | Exact |
|---|---|
| Memory | **The app uses `Debug.getPss(): Long`** (kB, own process, reads smaps on every call, not rate-limited; `MemorySampler`, 1 Hz). `ActivityManager.getProcessMemoryInfo(int[] pids): Debug.MemoryInfo[]` (kB; since Q only own uid, and throttled: AOSP returns the cached sample for `MEMORY_INFO_THROTTLE_TIME`, 5 min by default, so the app does NOT use it); `Debug.MemoryInfo.getTotalPss(): Int` (kB) and `getMemoryStat("summary.total-pss")` (API 23; other keys `summary.java-heap, summary.native-heap, summary.code, summary.stack, summary.graphics, summary.private-other, summary.system, summary.total-swap`) are the right "resident" numbers because PSS counts resident file-backed mmapped pages (the mmapped .pte); `Debug.getNativeHeapAllocatedSize()` (bytes) is malloc-only (model NOT in it); `Runtime.totalMemory()` is Java heap only; `Debug.getRss()` is **API 35 — absent on Galaxy XR (API 34) → guard `Build.VERSION.SDK_INT >= 35`** |
| Permissions | `PackageManager.getPackageInfo(String, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))` (API 33; `GET_PERMISSIONS = 4096`); `PackageInfo.requestedPermissions: String[]?` (null if none; only filled with GET_PERMISSIONS); `requestedPermissionsFlags` (API 16, `REQUESTED_PERMISSION_GRANTED = 2`). The merged APK keeps `WAKE_LOCK` only; any NET_PERMS entry (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE`) is a bug, so test the full set, not `INTERNET` alone (`ManifestAudit`) |

```kotlin
class Diagnostics(private val ctx: Context) {
    private val am = ctx.getSystemService(ActivityManager::class.java)
    fun memory(): String {                                              // 1 Hz is fine: Debug.getPss() is not throttled
        val pssMb = Debug.getPss() / 1024                               // getProcessMemoryInfo would be cached for minutes
        val rssMb = if (Build.VERSION.SDK_INT >= 35) Debug.getRss() / 1024 else -1L
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        return "PSS $pssMb MB" + (if (rssMb >= 0) " · RSS $rssMb MB" else "") + " · native heap $nativeHeapMb MB"
    }
    fun networkPermission(): String {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        val net = info.requestedPermissions.orEmpty().filter { it in NET_PERMS }   // INTERNET, ACCESS_NETWORK_STATE, …
        return "Network permission: " + if (net.isNotEmpty()) net.joinToString() else "none"   // ADR-0010: expect "INTERNET, ACCESS_NETWORK_STATE"; anything more is a bug
    }
}
```

---

## 4. Device facts

### Samsung Galaxy XR (primary device)

| Item | Value | Source | Confidence |
|---|---|---|---|
| SoC / RAM / storage | Snapdragon XR2+ Gen 2; 16 GB; 256 GB (single SKU) | https://news.samsung.com/global/introducing-galaxy-xr-opening-new-worlds | high (primary) |
| Sensors | 2 high-resolution passthrough cameras, 6 world-facing tracking cameras, 4 eye-tracking cameras, 5 IMUs, 1 depth sensor, 1 flicker sensor; capture camera 18 mm F2.0 6.5 MP ("resolution may vary depending on use case"); no passthrough pixel resolution published | same | high (primary) |
| OS | **Android 14 (API 34)** on every build: I610UEU1AYKE (2025-12-09), I610UEU2AZCI (2026-04-08), I610UEU2AZD8 (2026-05-06), **I610UEU2AZF3 (2026-07-07, SPL 2026-06-01, latest)**. README row: `adb shell getprop ro.build.version.release ro.build.version.sdk ro.build.display.id` → expect `14 / 34` | https://doc.samsungmobile.com/SM-I610/036371251209/eng.html (SYN curl; DV-PS) | high (primary) |
| Play / Jetpack floors | Play: Android XR apps must target API 34+ (deadline 2026-08-31); Jetpack XR: "All Jetpack XR APIs continue to require API 34 at runtime" (xr-compose 1.0.0-alpha02 notes) | https://support.google.com/googleplay/android-developer/answer/11926878?hl=en ; https://developer.android.com/jetpack/androidx/releases/xr-compose | high |
| Battery / weight | external 302 g pack; up to 2 h general use / 2.5 h video; "can be used while charging the battery"; headset 545 g; Wi-Fi 7, BT 5.4 | Samsung Newsroom (above) | high (primary) |
| Price / launch | US$1,799 (Google blog: "$1799 or $149/month"); Oct 21 2025 US / Oct 22 2025 KR. Price is NOT on the Samsung Newsroom page | https://blog.google/products/android/samsung-galaxy-xr/ | high |
| USB-C | headset port under a cover on the side: "It does not support charging (data only)"; battery pack charges separately and the pack can charge while the headset is in use. "Right strap" wording is UploadVR's | https://www.samsung.com/us/support/troubleshoot/TSG10007584/ (primary); https://uploadvr.com/samsung-galaxy-xr-hidden-usb-c-port (secondary) | high / medium |
| Developer mode | Settings > About Device > Build Number ×7 > PIN; Settings > System > Developer Options > USB Debugging; accept "Allow USB debugging" on the headset; use a data cable into the HEADSET port. Wireless: Developer options > Wireless debugging > Pair using pairing code → `adb pair ip:port` | https://help.managexr.com/en/articles/14128691-samsung-galaxy-xr-device-enrollment-guide (secondary); https://developer.android.com/tools/adb (primary for adb) | medium (menu labels from a partner guide; Samsung has no developer page for the headset) |
| Thermal | operating range 32–86 °F; iFixit teardown shows a fan + vapor chamber; Samsung's only slowdown remedies are update + restart | https://www.samsung.com/us/support/answer/ANS10007502/ ; https://www.ifixit.com/News/115196/galaxy-xr-teardown-is-this-the-1800-vision-pro-killer | medium |
| World camera for apps | announced (camera_id=0 via Camera2/CameraX, normal CAMERA permission) in Feb 2025; no official doc page; no post-launch developer report found; Unity's Android XR package explicitly lacks camera frames | https://skarredghost.com/2025/02/07/android-xr-camera-access-2/ (secondary) | low-medium (unverified on device) |
| Depth / planes / spatial audio on this device | docs list "XR Headsets"; beta blog says test on Galaxy XR; no per-device matrix | https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/depth | medium (unverified on device) |

Demo-day plan derived from the above (not a Samsung recommendation): keep the pack charging until the slot, restart
the headset before the demo, keep the 90-s demo well inside the 2 h rating, install over USB/Wi-Fi BEFORE airplane mode.

### Samsung Galaxy S25 Ultra (fallback device)

| Item | Value | Source | Confidence |
|---|---|---|---|
| SoC / RAM / battery | Snapdragon 8 Elite for Galaxy; 12 GB on all US SKUs (256 GB / 512 GB / 1 TB); 5000 mAh. 16 GB exists only on the 1 TB SKU in KR/CN/HK/TW | https://www.samsung.com/us/smartphones/galaxy-s25-ultra/ (primary); https://en.wikipedia.org/wiki/Samsung_Galaxy_S25_Ultra (secondary, 16 GB markets) | high / medium |
| OS | launched Android 15 / One UI 7 (Feb 7 2025); One UI 9 = **Android 17 (API 37)** rolling out in Korea from 2026-09-28 (build S938NKSUCDZIF); US timing unsourced; a loaned unit may be API 36 (Android 16) or 37 → record Settings > About phone > Software information | https://tech-ish.com/2026/09/30/galaxy-s25-z-fold7-and-z-flip7-start-getting-stable-android-17/ (secondary); https://developer.android.com/about/versions/17 (API 36 → 37) | medium |
| Developer options | Settings > About phone > Software information > Build number ×7 | https://developer.android.com/studio/debug/dev-options | high |

### Hackathon

| Item | Value | Source | Confidence |
|---|---|---|---|
| Event | ExecuTorch Hackathon, Oct 17–18 2026, Founders Inc San Francisco Lab, 2 Marina Blvd B300, San Francisco CA 94123; part of Open Source AI Week 2026; teams of 3–5; up to 200 participants; Mobile + XR track devices Galaxy S25 Ultra (Snapdragon 8 Elite) and Galaxy XR (XR2+ Gen 2) | https://luma.com/executorch-hackathon | high |
| Timing | Luma event window `2026-10-17T17:00Z` (Sat 10:00 PDT) → `2026-10-19T02:00Z` (Sun 19:00 PDT); OSAW: "demo night closes the hackathon on Sunday" (no clock time) | https://opensourceaiweek.com/the-hackathon/ | high |
| Rubric / submission format / judging slot | NOT published on Luma or OSAW; the SRS's photographed 100-point rubric stands; confirm format Sat morning | both pages (grep: no match) | — |
| Hosts / sponsors | Luma and OSAW list different splits (Luma: HackerSquad, Qualcomm, GitHub, Meta hosting; Meta, Qualcomm, GitHub, Open Intelligence Foundation, PyTorch Foundation sponsoring); do not quote either as definitive | both | medium |

---

## 5. Open uncertainties (yes/no to check on the device Saturday)

| # | Question (yes/no) | Owner / when | If no |
|---|---|---|---|
| Q1 | Does a fresh clone with exactly the pins in section 2 (executorch 1.3.1 + xr runtime rc02, the two artifacts that differ from the verified build) run `./gradlew assembleDebug testDebugUnitTest` green? | R5 Sat 10:30 | bump `executorch = "1.5.1"` and/or `xrRuntime = "1.1.0-alpha01"` (both resolved by other verifiers); fallback `kotlin = "2.2.10"` |
| Q2 | Does `adb shell getprop ro.build.version.sdk` print `34` on the loaned Galaxy XR (and is the build I610UEU2AZF3 or newer)? | R5 Sat 10:30 | if > 34, nothing breaks; if < 34, Jetpack XR will not run — stop |
| Q3 | Does CameraX deliver frames from Camera2 id "0" with only `CAMERA` granted (log `cameraIdList`, resolution, format, fps)? | R1 Sat 11:00 | Read/Describe move to the S25 Ultra; Navigation stays on the headset with depth/planes |
| Q4 | Does the app launch directly into Full Space with passthrough visible while a `SpatialPanel` is shown (`PROPERTY_XR_ACTIVITY_START_MODE = FULL_SPACE_MANAGED`)? | R1 Sat 11:00 | call `session.scene.requestFullSpace()` + `preferredPassthroughOpacity = 1.0f` explicitly |
| Q5 | Does `Session.configure` return `SessionConfigureSuccess` with plane tracking + depth + hands + device tracking all enabled after the four XR permissions are granted (no SecurityException / UnsupportedOperationException)? | R3 Sat 12:00 | drop modes one at a time; `XrDevice.isDepthEstimationModeSupported()` tells which |
| Q6 | Does `Depth.left(session)` (stereo) or `Depth.mono(session)` deliver a non-null `smoothDepthMap` on the headset? | R3 Sat 12:00 | proximity from plane hit tests + detector box height |
| Q7 | Does `Module.load("<filesDir>/yolo26n_int8_xnnpack.pte", LOAD_MODE_MMAP)` succeed and report `getBackends() == ["XnnpackBackend"]` with the 1.3.1 AAR (no from-source build)? | R2 Sat 11:00 | check `Module.readLogBufferStatic()`; confirm the .pte was exported with executorch==1.3.1 |
| Q8 | Has R2 chosen between Gemma 3 4B via optimum-executorch (`LlmModule`, option C) and a Kotlin `Module`-level port of Gemma 4 (option A)? (The SRS "Android multimodal runner" path for Gemma 4 is impossible, D5) | R2 Sat 11:00 | — |
| Q9 | If option C: is `google/gemma-3-4b-it` license accepted + HF token in hand, and does the exported .pte load with `LlmModule(MODEL_TYPE_MULTIMODAL…)` and answer an image prompt on the headset within the 4 GB PSS budget? | R2 Sat 16:00 | option A, or cut VLM to the S25 Ultra |
| Q10 | Does the VLM reach first spoken token ≤ 6 s (read) / ≤ 8 s (describe) when streaming `onResult` into TTS with `seqLen` capped? | R2 Sat 16:00 | smaller image (fewer soft tokens), cap tokens at 60, or swap model |
| Q11 | Does `SpatialSoundPool.play` with `USAGE_ASSISTANCE_SONIFICATION` produce an audibly positioned ping from an `AnchorSpace` entity through the headset speakers? | R3 Sat 13:00 | stereo-panned SoundPool from bearing |
| Q12 | Does `SpeechRecognizer.isOnDeviceRecognitionAvailable()` return true on Galaxy XR, and does `checkRecognitionSupport` list `en-US` and `es-MX` under `installedOnDeviceLanguages` in airplane mode? | R4 Sat 14:00 | `triggerModelDownload` once on Wi-Fi; else pinch-to-talk + panel buttons are P0, voice becomes P1 |
| Q13 | Does `TextToSpeech.getVoices()` contain an `es-MX` or `es-ES` voice with `isNetworkConnectionRequired == false` and no `"notInstalled"` feature? | R4 Sat 14:00 | install Google TTS Spanish pack on Wi-Fi before airplane mode |
| Q14 | Does TalkBack announce controls inside a `SpatialPanel` (contentDescription read aloud)? | R4 Sun 09:00 | duplicate labels as spoken cues |
| Q15 | Does `Hand.left/right(session).state` report `TRACKING` with 26 joints so pinch-and-hold (< 0.05 m thumb–index) works as push-to-talk? | R1 Sun 10:00 | panel mic button |
| Q16 | Does the merged APK (aapt2 dump badging) list no network permission beyond `INTERNET` and `ACCESS_NETWORK_STATE` (ADR-0010), and does the overlay print exactly `ACCESS_NETWORK_STATE, INTERNET`? | R5 after every merge | find the library; add a `tools:node="remove"` guard for the extra permission |
| Q17 | Does `./gradlew :app:lintDebug` pass once the OptIn (D6) and camera `uses-feature` are in, so lint can be promoted from `continue-on-error` in ci.yml? | R5 Sat | leave the flag |
| Q18 | Is the submission format and judging slot confirmed in writing by the organizers (Luma/OSAW publish only "demo night Sunday")? | R5 Sat 10:30 | — |
| Q19 | Does the ADB connection to the headset's data-only side port work from each laptop, and does Wi-Fi ADB pairing survive until airplane mode is toggled? | R5 Sat 10:30 | USB only; install before airplane mode |
| Q20 | Does the 4 GB `.pte` pushed to `/sdcard/Android/data/com.vysion.xr/files/` pass `validateFilePath` and load with `LOAD_MODE_MMAP` (not verified on Galaxy XR by anyone)? | R2 Sat 14:00 | copy into `filesDir` via the app |

UNVERIFIED items carried in this document (no verifier touched them): `androidx.xr.runtime:runtime-testing`
coordinate; `SpatialExternalSurface(stereoMode = StereoMode.Mono)` names; Gemma 3 4B preprocessing numbers
(896×896, 256 tokens, mean=std=0.5); `ImageProxy.toBitmap()` "added in 1.3.0"; the S25 Ultra US One UI 9 rollout date;
the Qualcomm guide's "About Headset > Software Information" menu label.

---

## Corrections found at build time

Append a row whenever a device run or a Gradle build proves a line above wrong. Newest last.

| Date | Line above | What was wrong | What is right | Evidence |
|---|---|---|---|---|
| | | | | |
| 2026-10-17 | §3 R3 "Head pose" (`RenderViewpoint.left/right/mono(session).state.value … fieldOfView`) | Names the property but not its type, which invites a non-existent `androidx.xr.runtime.FieldOfView` import | `fieldOfView` is `androidx.xr.runtime.math.FieldOfView(angleLeft, angleRight, angleUp, angleDown)` (four `Float`s; radians per OpenXR `XrFovf`, left/down negative, unit still UNVERIFIED on the device) | javap on the cached rc02/1.1.0-alpha01 jars (`androidx/xr/runtime/math/FieldOfView.class`; no class of that name in `androidx.xr.runtime`) |
| 2026-10-17 | §3 R3 "Config" (`DeviceTrackingMode {DISABLED, SPATIAL} (+experimental INERTIAL)`) | Incomplete list of modes | `DeviceTrackingMode` has `DISABLED`, `SPATIAL`, `LAST_KNOWN`, `SPATIAL_LAST_KNOWN`, `INERTIAL`, `INERTIAL_LAST_KNOWN`; Vysion uses `SPATIAL` | javap on the cached rc02/1.1.0-alpha01 jars (`javap androidx.xr.runtime.DeviceTrackingMode`) |
| 2026-10-17 | §3 R3 "Create" / "Configure" (the `Session` surface) | Omits two public members R3 needs | `Session` also exposes `coroutineScope: CoroutineScope` and `activity: Activity` (public getters) besides `config` / `configure` | javap on the cached rc02/1.1.0-alpha01 jars (`javap androidx.xr.runtime.Session`) |
| 2026-10-17 | §1 D2 (`androidx.xr.runtime:runtime` 1.0.0-rc02: "1.1.0-alpha01's only change is `SPATIAL_API_V4`") | Not the only difference: the internal `androidx.xr.runtime.internal.XrInstanceManager` also changed (lazy native-provider init) | The public API is identical apart from `SpatialApiVersions.SPATIAL_API_V4` / `LATEST_STABLE_API_LEVEL = 4`; `Session`, `DeviceTrackingMode` and `math.FieldOfView` class files are byte-identical. The build resolves runtime **1.0.0-rc02** and both AARs are now in the Gradle cache, so the pin stands | javap on the cached rc02/1.1.0-alpha01 jars (`cmp` of the class files, `javap -p -c` diff of `XrInstanceManager`); `./gradlew :app:dependencies --configuration debugRuntimeClasspath` |
| 2026-10-17 | §2.7 manifest (one `INTERNET` guard only) and §3-R5 "Permissions" ("test for `android.permission.INTERNET` specifically: the merged APK legitimately contains `ACCESS_NETWORK_STATE`") | `ACCESS_NETWORK_STATE` was treated as a legitimate merged permission and the overlay snippet tested `INTERNET` alone | Both `INTERNET` and `ACCESS_NETWORK_STATE` are removed with `tools:node="remove"`; the merged APK keeps `WAKE_LOCK` only; `ManifestAudit` and `scripts/check_privacy.sh` flag the full NET_PERMS set (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE`) | `javap -c -p` of `androidx.media3.common.util.NetworkTypeObserver` in `media3-common-1.10.0.aar` (the `getActiveNetworkInfo()` call sits in a `SecurityException` handler that returns "unknown"); merged manifest `app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml` lists neither permission |
| 2026-10-17 | §3-R5 "Memory" (`getProcessMemoryInfo` … "poll ≤ every ~5 s") | The throttle is far longer than 5 s for apps: AOSP caches the calling process's sample for `MEMORY_INFO_THROTTLE_TIME` (5 min default), so a 1 Hz overlay would freeze | `MemorySampler` uses `Debug.getPss()` (own process, not rate-limited, same total PSS) | AOSP `ActivityManagerService.getProcessMemoryInfo` (`MEMORY_INFO_THROTTLE_TIME`); `MemorySampler.kt` KDoc |
| 2026-10-17 | §2.4 `app/build.gradle.kts`, §2.7 manifest, §2.8 `ci.yml` | The ready-to-paste copies had drifted from the repo (no `USE_FAKES` `buildConfigField` / `buildConfig = true` / `testOptions`, no `configChanges`, no privacy or cue CI steps) | §2.4, §2.7 and §2.8 now hold the repo files verbatim | `diff` against `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `.github/workflows/ci.yml` |
| 2026-10-18 | §2.7 manifest, its "Manifest notes", Q16 and the §3-R5 `networkPermission` snippet | Still showed the ADR-0003 form: `tools:node="remove"` guards on `INTERNET` and `ACCESS_NETWORK_STATE`, "Network permission: none", and a `(BUG)` suffix on any network permission | ADR-0010 declares `INTERNET` and `ACCESS_NETWORK_STATE`; §2.7 is the repo file verbatim again (including FR-N9's two location permissions); Q16 and the snippet expect exactly those two, and any other network permission is the bug | `diff` against `app/src/main/AndroidManifest.xml`; `scripts/check_privacy.sh` |
| 2026-10-17 | §3 R3 "Hit test" / "Anchors" (`Ray` / `Pose` / `Vector3` in `androidx.xr.runtime.math`, no constructors given) and the `XrPerceptionSession` snippets (`v3()`, `toTracking()`, `toPlaneKind()`) | No constructor or type-mapping row; the snippets call bridge functions that exist nowhere | `Vector3(x: Float, y: Float, z: Float)` with `x/y/z`; `Pose(translation: Vector3 = Vector3(), rotation: Quaternion = Quaternion())` (default `Quaternion()` = (0, 0, 0, 1), identity) plus a `Pose(Vector3)` overload; `Vector2(x, y)`. `arcore.TrackingState`, `arcore.PlaneLabel` and `scenecore.SpatialCapability` are final classes with static instances, not enums, so every `when` over them needs `else`. R3 writes the bridges as private functions in `XrPerceptionSession.kt`: `Vector3.v3()`, `Vec3.toVector3()`, `TrackingState.toTracking()`, `PlaneLabel.toPlaneKind()` (code in its class KDoc) | javap on the cached runtime / arcore / scenecore 1.0.0-rc02 jars (`javap -c` of the `Pose` and `Quaternion` default constructors) |
| 2026-10-17 | §3 R3 code block, step 1 (`speaker.error("XR session unavailable")`) and step 5 (`speaker.say(Cues.NO_DOOR)`) | Neither call exists (`Speaker` has no `error`; `Cues` has `doorNotFound`, no `NO_DOOR`), and R3 holding a `Speaker` breaks INV-4 and docs/contracts.md ("R3 never builds strings") | R3 never speaks: an unavailable session sets `SessionState.UNAVAILABLE`; a door with no hit makes `placeFromBearing` return `null` and R1 speaks `ModePhrasebook.doorNotFound`. The step 6 `pool.load(assets.openFd(…), 0)` needs an `AssetManager` (`XrPerceptionSession` must expose `internal val assets`) and should pass priority `1` (Android's documented value) | `core/Contracts.kt` (`Speaker`), `voice/Cues.kt`, docs/modules/r3-spatial-perception.md (adapted snippet) |
| 2026-10-17 | §3 R3 "Depth" (`smoothDepthMap: FloatBuffer?` (meters)) | Does not say whether a value is the distance along the pixel's ray or along the optical axis, which changes FR-N3's off-axis door distance by up to 1/cos(angle) | still UNVERIFIED: on the first device run compare `sampleDepthAlongBearing` at ±30° against a tape measure; if the map is axial, divide by the cosine of the angle off the axis (`XrPerceptionSession.sampleDepthAlongBearing` KDoc) | none yet |
| 2026-10-17 | §5 Q3 ("log `cameraIdList`, resolution, format, fps") | Nobody measured the camera's horizontal FOV, which R3's `BearingAngles` (left / center / right → degrees) needs; the still is center-cropped, so the full-sensor FOV is the wrong number | Q3 also logs the horizontal FOV of the cropped still (R1): `hfov = 2·atan(SENSOR_INFO_PHYSICAL_SIZE.width / (2·LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0]))`, `hfovCrop = 2·atan((H/W)·tan(hfov/2))` for the square crop of a W×H frame, logged as `vysion: still hfov=<deg> deg (A-1)`. R3 then sets `BearingAngles.LEFT_DEG / RIGHT_DEG = ∓atan((2/3)·tan(hfovCrop/2))` and records it in docs/assumptions.md A-1 | `CameraFrameSource.start()` KDoc; `XrAnchorStore.kt` `BearingAngles` KDoc |
| 2026-10-17 | §5 Q11 "If no" ("stereo-panned SoundPool from bearing") | Disagrees with docs/assumptions.md A-6, which is what the code implements | If no: `DoorBearing.pinged = false`, R1 plays the non-spatial `Earcon.PING` through the `Speaker` and the guidance period drops to 2 s (assumptions.md A-6). No stereo pan is planned | `XrNavigationEngine` (`FALLBACK_GUIDANCE_PERIOD_MS`), `ModeController.onCue`, `XrNavigationEngineTest` |
| 2026-10-17 | §5 (missing question) | Nothing asks whether `Session.create(context = activity)` (R3, `XrPerceptionSession`) and Compose for XR's `LocalSession.current` (R1, `SpatialShell`) are the same session; the SRS says the ARCore session is "created from the Compose for XR session" | **Q21** (R3 + R1, Sat 12:00): log `System.identityHashCode` of both on the first headset run. If they differ and either misbehaves (no `scene` capabilities, no audio from an `AnchorSpace` entity), R1 hands `LocalSession.current` to `AppGraph.startPerception` and R3 configures it instead of creating its own (announced like a contract change). If no: two sessions coexist and nothing changes | docs/modules/r3-spatial-perception.md Contract issue 7, r1 page open issue 11 |
| 2026-10-17 | §3 R1 "Provider" (`bindToLifecycle(LifecycleOwner, …)`, no owner given) | `FrameSource.start()` has no Activity, and no snippet showed where the `LifecycleOwner` comes from | An owner the source drives itself: `private val owner = object : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }`; in `start()` on `Dispatchers.Main`: INITIALIZED → CREATED and `bindToLifecycle(owner, …)` once, then STARTED; in `stop()` on Main: CREATED; never DESTROYED (stop is reversible). `LifecycleRegistry(this)` enforces the main thread on state changes | javap on the cached lifecycle-runtime-android 2.11.0 (`LifecycleRegistry(LifecycleOwner)`, `getCurrentState`, `setCurrentState`) and lifecycle-common-jvm 2.11.0 (`LifecycleOwner.getLifecycle()`); `CameraFrameSource.start()` KDoc |
| 2026-10-17 | §3 R1 "Bitmap" ("rotation via `imageInfo.rotationDegrees`") | No snippet, no log line, and nothing says the value must be read before the `ImageProxy` closes | Inside `onCaptureSuccess`: `val r = image.imageInfo.rotationDegrees; val raw = image.use { it.toBitmap() }`; if `r != 0`, `Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(r.toFloat()) }, true)` and recycle `raw`; then `CenterCrop`. Log once `vysion: still <w>x<h> rotation=<r> -> <edge>x<edge> (A-1)` from locals (not `bitmap.` / `frame.`, which `check_privacy.sh` §4 flags in a log call) | `CameraFrameSource.captureStill()` KDoc |
| 2026-10-17 | §3-R2 "VLM path" (`vlm.generate(PromptLibrary.READ, …)` after `prefillImages`) | Silent on Gemma 3's chat template: nobody checked whether `LlmModule`'s multimodal runner or the optimum-executorch `.pte` applies it, or where the image goes relative to the user turn | UNVERIFIED. On the first device answer (S25 Ultra or headset) A/B two forms on the same printed sign: (a) raw: `prefillImages(…)` then `generate(READ, …)`; (b) wrapped: `prefillPrompt("<start_of_turn>user\n")`, `prefillImages(…)`, then `generate(READ + "<end_of_turn>\n<start_of_turn>model\n", …)`. Keep the one that answers with only the sign's text; the wrapping lives in `ExecuTorchInferenceEngine.prefillLocked` / `generateLocked`, never in `PromptLibrary`. Replace this row's "UNVERIFIED" with the result | ml/README.md §3 "Prompt format"; `generateLocked` KDoc |
| 2026-10-17 | §3-R2 "VLM path" (`prefillImages(rgbChwUint8 …)   // or prefillNormalizedImage(…)`) with §5's "mean=std=0.5" | Two inputs offered, no rule for picking one | UNVERIFIED. Start with `prefillImages(IntArray uint8 CHW, w, h, 3)` (the engine's KDoc), which is right if the exported `vision_encoder` normalizes internally; if the answer to a printed sign is garbage, switch to `prefillImages(FloatArray CHW, w, h, 3)` with `((v / 255) - 0.5) / 0.5` per channel. Accept when `onStats` `prompt_tokens` = `IMAGE_SOFT_TOKENS` (256 expected) + the prompt's tokens and the sign is read correctly; record the overload here | ml/README.md §3 "Image input"; `prefillLocked` KDoc |
| 2026-10-17 | D1 / §3-R2 | Runtime bumped to `executorch-android:1.4.0`. Device-verified on the Galaxy XR: the 1.3.1 detector export, Software Mansion's PP-OCRv6 `.pte` (exported with 1.4.1) and LFM2.5-VL-450M v0.11.0 all load. The 1.4 AAR is Kotlin: `Tensor.dataAsFloatArray` and `MethodMetadata.backends` are properties now. Python exports stay on `executorch==1.3.1` (`ml/export/Dockerfile`), one minor back is supported. | Claude (Isaac's laptop) |
| 2026-10-17 | §3-R2 LlmModule | Software Mansion's LFM2.5-VL `.pte` (v0.9.0 and v0.11.0) has a STATIC `token_embedding` input `[1, 2048]` (`enable_dynamic_shape` = false); their runner pads ids to 2048. The stock `LlmModule` runner passes the real length, so `prefillPrompt` / `prefillImages` SEGV in `libexecutorch_jni.so` (heap read past the ids). Resolved: each method runs fine through plain `Module` (dynamic `[1, n]` embedding included), so `inference/VlmRunners.kt` `ModuleVlmRunner` drives them itself with a Kotlin BPE tokenizer; the stock-runner fault is somewhere in its prefill, not in the model. | Claude (Isaac's laptop) |
| 2026-10-18 | ADR-0002 Context ("a Hexagon NPU reachable through the ExecuTorch Qualcomm (QNN) delegate"); `docs/roadmap-research.md` §2 | Assumed a normal app can reach the cDSP. ExecuTorch v1.4.0's `examples/qualcomm/qnn-htp-test` prints "SUCCESS! backendCreate returned 0 … cDSP FastRPC WORKS from untrusted_app" on this headset, but that is a false positive: `backendCreate` opens nothing; `libQnnHtpV69Stub.so` creates the unsigned PD at `deviceCreate` ("Cannot change signed/unsigned PD after device create") | **No-go: a third-party app cannot run anything on the NPU (I610UEU2AZF3, SELinux enforcing).** Probe APK as set up in roadmap §2 (QAIRT 2.37 `libQnnHtp` / `libQnnSystem` / `libQnnHtpV69Stub` / `libQnnHtpV69Skel` from Maven `qnn-runtime:2.37.0`, `extractNativeLibs` true, `<uses-native-library libcdsprpc.so>`, `ADSP_LIBRARY_PATH` = `nativeLibraryDir`) plus a test that goes on to `deviceCreate` / `contextCreate` / a one-op graph: `libcdsprpc.so` loads, `backendCreate` = 0, `deviceGetPlatformInfo` = 0 (socModel 53, arch v69, 8 MB VTCM; a table in `libQnnHtp.so`, no DSP call), **`deviceCreate` = 14001**: FastRPC cannot open the cDSP (EACCES) and the DSP-HAL fallback (`IDspManager`) is not on the device. The headset has no `/dev/fastrpc-cdsp`; `/dev/adsprpc-smd` (`crw-rw-r-- system system vendor_qdsp_device`) opens from `adb shell` and not from the app, with the same "other" permission bits, so SELinux denies `untrusted_app` (no avc line is logged). Even `adb shell` cannot start an unsigned PD: the cDSP shell under `/vendor/dsp/` is unreadable. Every other public route uses the same FastRPC (`libSNPE.so`, `libfastcvopt.so` in `/vendor/etc/public.libraries.txt`); there is no NNAPI HAL; the NPU's client is Google's `com.google.xr.hardware.dnn.IDnnService` (vendor, no app API). So: no `executorch-android-qnn`, no QNN export; detector and VLM stay on XNNPACK (ADR-0002 unchanged). AGP 9 also refuses `android:extractNativeLibs="true"` in the manifest; it is `packaging.jniLibs.useLegacyPackaging = true`. Re-check after an OTA: `ml/qnn-probe/probe.sh` (25 s, uninstalls itself) | Device runs on R3GYB0225HT. App (`untrusted_app`): `fastrpc_apps_user.c:2902: open_device_node: no access to default device of domain 3, open thru HAL` · `DspClient: unable to acquire dspservice instance` · `openSession: IDspManager session is NULL` · `open_device_node failed for domain ID 3, sess ID 0 (errno 13, Permission denied)` · `QnnDsp <E> createUnsignedPD unsigned PD or DSPRPC_GET_DSP_INFO not supported by HTP` · `DspTransport.createUnsignedPD failed, 0x00000003` · `Failed to load skel, error: 1002` · `[4] deviceCreate (opens the unsigned PD on the cDSP): 14001 QNN_DEVICE_ERROR_INVALID_CONFIG`. `adb shell` (`u:r:shell:s0`): `remote_session_control Unsigned PD enable 1 request for domain 3` · `open_shell failed for domain 3 search paths used are /dsp/, /vendor/dsp/, /vendor/dsp/xdsp/ (errno Permission denied)` · `Error 0x80000580: remote_handle64_open failed for file:///libQnnHtpV69Skel.so?qnn_skel_handle_invoke&_modver=1.0&_dom=cdsp` · `deviceCreate` 14001. Claude (Isaac's laptop) |
