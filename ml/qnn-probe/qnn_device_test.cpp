// NPU go/no-go probe for the Galaxy XR (SXR2230P, HTP v69), run by ml/qnn-probe/probe.sh. docs/stack.md Corrections.
//
// ExecuTorch's examples/qualcomm/qnn-htp-test stops at backendCreate, which never opens FastRPC on this headset: the
// stub creates the unsigned PD at deviceCreate ("Cannot change signed/unsigned PD after device create" in
// libQnnHtpV69Stub.so), so its "SUCCESS" is a false positive here. This test makes the calls an ExecuTorch QNN load
// makes, with the QAIRT 2.37 headers: platform info, deviceCreate, contextCreate, then a one-op uint8 ElementWiseAdd
// finalized (online prepare, libQnnHtpPrepare.so) and executed on the HTP. Built twice: as the JNI library of the
// probe APK (untrusted_app, what Vysion runs as) and with -DSTANDALONE as an adb-shell binary (u:r:shell:s0).

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <sstream>
#include <string>

#include "HTP/QnnHtpDevice.h"
#include "QnnInterface.h"
#include "QnnOpDef.h"

#define TAG "QnnHtpDeviceTest"

static std::ostringstream* gLog = nullptr;

static void out(const char* fmt, ...) {
  char buf[1024];
  va_list a;
  va_start(a, fmt);
  vsnprintf(buf, sizeof(buf), fmt, a);
  va_end(a);
  __android_log_print(ANDROID_LOG_INFO, TAG, "%s", buf);
#ifdef STANDALONE
  printf("%s\n", buf);
#endif
  if (gLog) *gLog << buf << "\n";
}

static void qnnLog(const char* fmt, QnnLog_Level_t level, uint64_t, va_list args) {
  char buf[1024];
  vsnprintf(buf, sizeof(buf), fmt, args);
  size_t n = strlen(buf);
  if (n > 0 && buf[n - 1] == '\n') buf[n - 1] = '\0';
  __android_log_print(ANDROID_LOG_INFO, TAG, "[QNN L%d] %s", (int)level, buf);
#ifdef STANDALONE
  if (level <= QNN_LOG_LEVEL_WARN) printf("[QNN L%d] %s\n", (int)level, buf);
#endif
}

static const QNN_INTERFACE_VER_TYPE* q = nullptr;

static const char* msg(Qnn_ErrorHandle_t e) {
  const char* m = nullptr;
  if (q && q->errorGetMessage && q->errorGetMessage(e, &m) == QNN_SUCCESS && m) return m;
  return "(no message)";
}

static Qnn_Tensor_t u8Tensor(const char* name, Qnn_TensorType_t type, uint32_t* dims) {
  Qnn_Tensor_t t = QNN_TENSOR_INIT;
  t.version = QNN_TENSOR_VERSION_1;
  t.v1.name = name;
  t.v1.type = type;
  t.v1.dataType = QNN_DATATYPE_UFIXED_POINT_8;
  t.v1.quantizeParams.encodingDefinition = QNN_DEFINITION_DEFINED;
  t.v1.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_SCALE_OFFSET;
  t.v1.quantizeParams.scaleOffsetEncoding.scale = 1.0f;
  t.v1.quantizeParams.scaleOffsetEncoding.offset = 0;
  t.v1.rank = 4;
  t.v1.dimensions = dims;
  t.v1.memType = QNN_TENSORMEMTYPE_RAW;
  return t;
}

static std::string runDeviceTest(const char* dir) {
  std::ostringstream log;
  gLog = &log;
  setenv("ADSP_LIBRARY_PATH", dir, 1);
  out("=== QNN HTP device test (deviceCreate / contextCreate / execute) ===");

  Qnn_LogHandle_t lh = nullptr;
  Qnn_BackendHandle_t be = nullptr;
  Qnn_DeviceHandle_t dev = nullptr;
  Qnn_ContextHandle_t ctx = nullptr;
  Qnn_ErrorHandle_t e;

  void* lib = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
  if (!lib) { out("FAIL dlopen libQnnHtp.so: %s", dlerror()); goto done; }
  {
    auto getProviders = (Qnn_ErrorHandle_t(*)(const QnnInterface_t***, uint32_t*))dlsym(lib, "QnnInterface_getProviders");
    const QnnInterface_t** providers = nullptr;
    uint32_t n = 0;
    if (!getProviders || getProviders(&providers, &n) != QNN_SUCCESS || n == 0) { out("FAIL getProviders"); goto done; }
    q = &providers[0]->QNN_INTERFACE_VER_NAME;
    out("[1] provider %s core %u.%u backend %u.%u", providers[0]->providerName,
        providers[0]->apiVersion.coreApiVersion.major, providers[0]->apiVersion.coreApiVersion.minor,
        providers[0]->apiVersion.backendApiVersion.major, providers[0]->apiVersion.backendApiVersion.minor);

    e = q->logCreate(qnnLog, QNN_LOG_LEVEL_VERBOSE, &lh);
    e = q->backendCreate(lh, nullptr, &be);
    out("[2] backendCreate: %u %s", (unsigned)e, e ? msg(e) : "OK");
    if (e) goto done;

    const QnnDevice_PlatformInfo_t* pi = nullptr;
    e = q->deviceGetPlatformInfo(lh, &pi);
    out("[3] deviceGetPlatformInfo: %u %s", (unsigned)e, e ? msg(e) : "OK");
    if (!e && pi) {
      for (uint32_t i = 0; i < pi->v1.numHwDevices; i++) {
        const auto& d = pi->v1.hwDevices[i].v1;
        auto* ext = reinterpret_cast<QnnHtpDevice_DeviceInfoExtension_t*>(d.deviceInfoExtension);
        if (ext && ext->devType == QNN_HTP_DEVICE_TYPE_ON_CHIP) {
          out("    device %u: socModel=%u arch=v%u vtcm=%zuMB signedPd=%d cores=%u", d.deviceId,
              ext->onChipDevice.socModel, (unsigned)ext->onChipDevice.arch, ext->onChipDevice.vtcmSize,
              ext->onChipDevice.signedPdSupport, d.numCores);
        } else {
          out("    device %u: type=%u cores=%u", d.deviceId, d.deviceType, d.numCores);
        }
      }
      q->deviceFreePlatformInfo(lh, pi);
    }

    e = q->deviceCreate(lh, nullptr, &dev);
    out("[4] deviceCreate (opens the unsigned PD on the cDSP): %u %s", (unsigned)e, e ? msg(e) : "OK");
    if (e) goto done;

    e = q->contextCreate(be, dev, nullptr, &ctx);
    out("[5] contextCreate: %u %s", (unsigned)e, e ? msg(e) : "OK");
    if (e) goto done;

    Qnn_GraphHandle_t g = nullptr;
    e = q->graphCreate(ctx, "probe_add", nullptr, &g);
    out("[6] graphCreate: %u %s", (unsigned)e, e ? msg(e) : "OK");
    if (e) goto done;

    static uint32_t dims[4] = {1, 1, 1, 4};
    Qnn_Tensor_t a = u8Tensor("a", QNN_TENSOR_TYPE_APP_WRITE, dims);
    Qnn_Tensor_t b = u8Tensor("b", QNN_TENSOR_TYPE_APP_WRITE, dims);
    Qnn_Tensor_t c = u8Tensor("c", QNN_TENSOR_TYPE_APP_READ, dims);
    if (q->tensorCreateGraphTensor(g, &a) || q->tensorCreateGraphTensor(g, &b) || q->tensorCreateGraphTensor(g, &c)) {
      out("FAIL tensorCreateGraphTensor");
      goto done;
    }
    Qnn_Tensor_t ins[2] = {a, b};
    Qnn_Tensor_t outs[1] = {c};
    Qnn_OpConfig_t op = QNN_OPCONFIG_INIT;
    op.version = QNN_OPCONFIG_VERSION_1;
    op.v1.name = "add";
    op.v1.packageName = QNN_OP_PACKAGE_NAME_QTI_AISW;
    op.v1.typeName = QNN_OP_ELEMENT_WISE_ADD;
    op.v1.numOfInputs = 2;
    op.v1.inputTensors = ins;
    op.v1.numOfOutputs = 1;
    op.v1.outputTensors = outs;
    e = q->graphAddNode(g, op);
    out("[7] graphAddNode: %u %s", (unsigned)e, e ? msg(e) : "OK");
    if (e) goto done;
    e = q->graphFinalize(g, nullptr, nullptr);
    out("[8] graphFinalize (HTP prepare + load onto the DSP): %u %s", (unsigned)e, e ? msg(e) : "OK");
    if (e) goto done;

    uint8_t av[4] = {1, 2, 3, 4}, bv[4] = {10, 20, 30, 40}, cv[4] = {0, 0, 0, 0};
    ins[0].v1.clientBuf = {av, 4};
    ins[1].v1.clientBuf = {bv, 4};
    outs[0].v1.clientBuf = {cv, 4};
    e = q->graphExecute(g, ins, 2, outs, 1, nullptr, nullptr);
    out("[9] graphExecute: %u %s -> c = [%u %u %u %u] (expect 11 22 33 44)", (unsigned)e, e ? msg(e) : "OK",
        cv[0], cv[1], cv[2], cv[3]);
    if (!e && cv[0] == 11 && cv[1] == 22 && cv[2] == 33 && cv[3] == 44) out("RESULT: HTP EXECUTES from untrusted_app");
  }

done:
  if (q) {
    if (ctx) q->contextFree(ctx, nullptr);
    if (dev) q->deviceFree(dev);
    if (be) q->backendFree(be);
    if (lh) q->logFree(lh);
  }
  out("=== end ===");
  gLog = nullptr;
  return log.str();
}

#ifdef STANDALONE
// adb shell (u:r:shell:s0) build: ADSP_LIBRARY_PATH and LD_LIBRARY_PATH = the push dir.
int main(int argc, char** argv) { runDeviceTest(argc > 1 ? argv[1] : "/data/local/tmp/qnnprobe"); return 0; }
#else
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_qnntest_MainActivity_runQnnDeviceTest(JNIEnv* env, jobject, jstring dirJ) {
  const char* dir = env->GetStringUTFChars(dirJ, nullptr);
  std::string r = runDeviceTest(dir);
  env->ReleaseStringUTFChars(dirJ, dir);
  return env->NewStringUTF(r.c_str());
}
#endif
