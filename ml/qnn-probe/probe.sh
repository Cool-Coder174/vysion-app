#!/usr/bin/env bash
# NPU go/no-go probe: can a normal app (untrusted_app, what Vysion runs as) reach the Galaxy XR's Hexagon NPU (cDSP,
# HTP v69) through QNN? Result on I610UEU2AZF3, 2026-10-18: NO (docs/stack.md Corrections). Re-run after an OTA.
#
# What it builds: ExecuTorch v1.4.0's examples/qualcomm/qnn-htp-test, with
#   - the QAIRT 2.37 runtime libraries from Maven com.qualcomm.qti:qnn-runtime:2.37.0 (the one
#     org.pytorch:executorch-android-qnn:1.4.0 depends on): libQnnHtp, libQnnSystem, libQnnHtpV69Stub,
#     libQnnHtpV69Skel, plus libQnnHtpPrepare for the one-op graph;
#   - qnn_device_test.cpp instead of the upstream test, which stops at backendCreate (a false positive here, the
#     FastRPC session opens at deviceCreate);
#   - extractNativeLibs via packaging.jniLibs.useLegacyPackaging (AGP 9 refuses the manifest attribute),
#     <uses-native-library libcdsprpc.so>, ADSP_LIBRARY_PATH = nativeLibraryDir, and a run on launch (no scriptable
#     tap inside the headset).
# Then runs the same test as an adb-shell binary (u:r:shell:s0) to tell app policy apart from the DSP itself.
#
# Usage:   ml/qnn-probe/probe.sh [--serial <serial>] [--keep]     (--keep: leave the probe app and files installed)
# Prereq:  one adb device; NDK 28.2.13676358 (sdkmanager "ndk;28.2.13676358"); network for Maven, GitHub and the
#          QNN headers, read by HTTP range from Qualcomm's public QAIRT 2.37 zip (~1 MB of a 1.35 GB file).
# Output:  ml/out/qnn-probe/{app,shell}.log (full logcat of each run) and the verdict lines on stdout.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/ml/qnn-probe"
OUT="$ROOT/ml/out/qnn-probe"
WORK="$OUT/work"
QAIRT="2.37.0.250724"
QNN_RT="2.37.0"
ET_TAG="v1.4.0"
NDK_VER="28.2.13676358"
APP_ID="com.example.qnntest"
DEV_DIR="/data/local/tmp/qnnprobe"
SERIAL=""
KEEP=0

while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$(command -v adb || echo "$SDK/platform-tools/adb")"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
CXX="$(ls -d "$SDK/ndk/$NDK_VER"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -1)/aarch64-linux-android30-clang++"
[ -x "$CXX" ] || { echo "NDK $NDK_VER missing: sdkmanager \"ndk;$NDK_VER\"" >&2; exit 2; }
[ "$($ADB devices | grep -cw device)" -ge 1 ] || { echo "no adb device" >&2; exit 2; }
mkdir -p "$WORK"

# 1. QAIRT 2.37 runtime libraries (Maven) and QNN headers (range-read from the SDK zip).
LIBS="$WORK/libs"
if [ ! -f "$LIBS/libQnnHtp.so" ]; then
  curl -fsSL -o "$WORK/qnn-runtime.aar" \
    "https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/$QNN_RT/qnn-runtime-$QNN_RT.aar"
  mkdir -p "$LIBS"
  for l in libQnnHtp libQnnSystem libQnnHtpV69Stub libQnnHtpV69Skel libQnnHtpPrepare; do
    unzip -o -q -j "$WORK/qnn-runtime.aar" "jni/arm64-v8a/$l.so" -d "$LIBS"
  done
fi
INC="$WORK/qairt/$QAIRT/include/QNN"
if [ ! -f "$INC/QnnInterface.h" ]; then
  [ -f /etc/ssl/cert.pem ] && export SSL_CERT_FILE="${SSL_CERT_FILE:-/etc/ssl/cert.pem}"
  python3 -I - "https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/$QAIRT/v$QAIRT.zip" \
      "$WORK" "qairt/$QAIRT/include/QNN/" <<'PY'
import io, os, sys, urllib.request, zipfile
class Http(io.RawIOBase):  # seekable file over HTTP Range, so zipfile reads only the central directory + headers
    def __init__(self, url):
        r = urllib.request.urlopen(urllib.request.Request(url, headers={"Range": "bytes=0-0"}))
        self.url, self.size, self.pos = r.geturl(), int(r.headers["Content-Range"].split("/")[1]), 0
    def seekable(self): return True
    def readable(self): return True
    def tell(self): return self.pos
    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else self.pos + off if whence == 1 else self.size + off
        return self.pos
    def readinto(self, b):
        if self.pos >= self.size: return 0
        end = min(self.pos + len(b), self.size) - 1
        d = urllib.request.urlopen(urllib.request.Request(self.url, headers={"Range": f"bytes={self.pos}-{end}"})).read()
        b[:len(d)] = d; self.pos += len(d); return len(d)
url, out, prefix = sys.argv[1:4]
z = zipfile.ZipFile(io.BufferedReader(Http(url), 1 << 20))
for n in z.namelist():
    if n.startswith(prefix) and not n.endswith("/"):
        os.makedirs(os.path.dirname(os.path.join(out, n)), exist_ok=True)
        open(os.path.join(out, n), "wb").write(z.read(n))
PY
fi

# 2. The upstream example, adapted (see the header).
P="$WORK/qnn-htp-test"
if [ ! -d "$P" ]; then
  git -c advice.detachedHead=false clone -q --depth 1 --branch "$ET_TAG" --filter=blob:none --sparse https://github.com/pytorch/executorch.git "$WORK/et"
  git -C "$WORK/et" sparse-checkout set examples/qualcomm/qnn-htp-test
  cp -R "$WORK/et/examples/qualcomm/qnn-htp-test" "$P"
fi
cp "$ROOT/gradlew" "$P/" && cp "$ROOT"/gradle/wrapper/gradle-wrapper.{jar,properties} "$P/gradle/wrapper/"
echo "sdk.dir=$SDK" > "$P/local.properties"
AGP="$(sed -n 's/^agp = "\(.*\)".*/\1/p' "$ROOT/gradle/libs.versions.toml")"
cat > "$P/build.gradle" <<EOF
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath 'com.android.tools.build:gradle:$AGP' }
}
allprojects { repositories { google(); mavenCentral() } }
EOF
cat > "$P/app/build.gradle" <<'EOF'
plugins { id 'com.android.application' }
android {
    namespace = 'com.example.qnntest'
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.qnntest"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters 'arm64-v8a' }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets { main { jniLibs.srcDirs = ['src/main/jniLibs'] } }
    packaging { jniLibs { keepDebugSymbols.add('**/*.so'); useLegacyPackaging = true } }
}
EOF
python3 -I - "$P" <<'PY'
import sys
p = sys.argv[1] + "/app/src/main/AndroidManifest.xml"
s = open(p).read().replace('        android:extractNativeLibs="true"\n', "")
open(p, "w").write(s)
p = sys.argv[1] + "/app/src/main/java/com/example/qnntest/MainActivity.java"
s = open(p).read()
if "runQnnDeviceTest" not in s:
    s = s.replace("    private native String runQnnHtpTest(String nativeLibDir);",
                  "    private native String runQnnHtpTest(String nativeLibDir);\n"
                  "    private native String runQnnDeviceTest(String nativeLibDir);")
    s = s.replace("String result = runQnnHtpTest(nativeLibDir);", "String result = runQnnDeviceTest(nativeLibDir);")
    s = s.replace("        testButton.setOnClickListener(v -> {",
                  "        testButton.post(testButton::performClick);\n        testButton.setOnClickListener(v -> {")
open(p, "w").write(s)
PY
JNI="$P/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI" && cp "$LIBS"/*.so "$JNI/"
"$CXX" -shared -fPIC -O2 -static-libstdc++ -I"$INC" -o "$JNI/libqnn_test_jni.so" \
    "$P/app/src/main/jni/qnn_test.cpp" "$HERE/qnn_device_test.cpp" -llog -ldl
"$CXX" -DSTANDALONE -O2 -static-libstdc++ -I"$INC" -o "$WORK/qnn_device_probe" "$HERE/qnn_device_test.cpp" -llog -ldl
(cd "$P" && ./gradlew -q assembleDebug)

VERDICT='QnnHtpDeviceTest: (\[[0-9]\]|RESULT)|open_device_node failed|IDspManager|open_shell failed|remote_handle64_open failed'

# 3. As an app (untrusted_app).
APK="$P/app/build/outputs/apk/debug/app-debug.apk"
$ADB install -r "$APK" >/dev/null || { sleep 3; $ADB install -r "$APK" >/dev/null; }   # shared headset: retry once
$ADB shell am force-stop "$APP_ID"
$ADB shell am start -W -n "$APP_ID/.MainActivity" >/dev/null
sleep 8
PID="$($ADB shell pidof "$APP_ID" | tr -d '\r' || true)"
[ -n "$PID" ] || { echo "probe app not running (crashed?): adb logcat -d | grep -i qnntest" >&2; exit 1; }
$ADB logcat -d -v threadtime --pid="$PID" > "$OUT/app.log"
echo "== as an app (untrusted_app), pid $PID: $OUT/app.log"
grep -E "$VERDICT" "$OUT/app.log" | sed -E 's/^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [A-Z] //' || true

# 4. As adb shell (u:r:shell:s0).
$ADB shell mkdir -p "$DEV_DIR"
$ADB push -q "$WORK/qnn_device_probe" "$LIBS"/*.so "$DEV_DIR/"
$ADB shell "cd $DEV_DIR && chmod 755 qnn_device_probe && ADSP_LIBRARY_PATH=$DEV_DIR LD_LIBRARY_PATH=$DEV_DIR ./qnn_device_probe $DEV_DIR" \
  > "$WORK/shell.stdout" 2>&1 || true
$ADB logcat -d -v threadtime -t 2000 | grep -E "qnn_device_probe|QnnHtpDeviceTest" > "$OUT/shell.log" || true
echo "== as adb shell (u:r:shell:s0): $OUT/shell.log"
grep -E "^\[[0-9]\]|RESULT" "$WORK/shell.stdout" || true
grep -E "open_device_node failed|open_shell failed|remote_handle64_open failed" "$OUT/shell.log" \
  | sed -E 's/^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [A-Z] //' | tail -3 || true

if [ "$KEEP" -eq 0 ]; then
  $ADB uninstall "$APP_ID" >/dev/null || true
  $ADB shell rm -rf "$DEV_DIR"
fi
if grep -q "RESULT: HTP EXECUTES" "$OUT/app.log"; then echo "VERDICT: GO (an app ran a graph on the HTP)"; else echo "VERDICT: NO-GO (no graph ran on the HTP from an app)"; fi
