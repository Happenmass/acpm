#include <dlfcn.h>
#include <jni.h>
#include <onnxruntime_c_api.h>

#include <algorithm>
#include <array>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
constexpr jsize kSamples = 160000;
constexpr int64_t kClasses = 7;

const OrtApi& api() {
    // Use the same ONNX Runtime already loaded by the sherpa Android package.
    static const OrtApi* runtime = [] {
        auto* library = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) throw std::runtime_error("ONNX Runtime is not installed");
        auto getBase = reinterpret_cast<decltype(&OrtGetApiBase)>(dlsym(library, "OrtGetApiBase"));
        const auto* version = getBase ? getBase()->GetApi(ORT_API_VERSION) : nullptr;
        if (!version) {
            dlclose(library);
            throw std::runtime_error("ONNX Runtime C API is incompatible");
        }
        return version;
    }();
    return *runtime;
}

void check(OrtStatus* status) {
    if (!status) return;
    std::unique_ptr<OrtStatus, decltype(api().ReleaseStatus)> owned(status, api().ReleaseStatus);
    throw std::runtime_error(api().GetErrorMessage(status));
}

struct Model {
    explicit Model(const char* path) {
        OrtEnv* environment = nullptr;
        check(api().CreateEnv(ORT_LOGGING_LEVEL_ERROR, "android-ai-vad", &environment));
        env.reset(environment);
        OrtSessionOptions* options = nullptr;
        check(api().CreateSessionOptions(&options));
        std::unique_ptr<OrtSessionOptions, decltype(api().ReleaseSessionOptions)> owned(options, api().ReleaseSessionOptions);
        check(api().SetIntraOpNumThreads(options, 2));
        OrtSession* model = nullptr;
        check(api().CreateSession(env.get(), path, options, &model));
        session.reset(model);
    }
    std::unique_ptr<OrtEnv, decltype(api().ReleaseEnv)> env{nullptr, api().ReleaseEnv};
    std::unique_ptr<OrtSession, decltype(api().ReleaseSession)> session{nullptr, api().ReleaseSession};
};

void report(JNIEnv* jni, const std::exception& error) {
    if (!jni->ExceptionCheck()) jni->ThrowNew(jni->FindClass("java/lang/IllegalStateException"), error.what());
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_androidagent_speech_PyannoteVad_open(JNIEnv* jni, jobject, jstring path) {
    const char* chars = jni->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    std::unique_ptr<Model> model;
    try {
        model = std::make_unique<Model>(chars);
    } catch (const std::exception& error) {
        report(jni, error);
    }
    jni->ReleaseStringUTFChars(path, chars);
    return reinterpret_cast<jlong>(model.release());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_androidagent_speech_PyannoteVad_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Model*>(handle);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_androidagent_speech_PyannoteVad_classify(JNIEnv* jni, jobject, jlong handle, jfloatArray input) try {
    if (jni->GetArrayLength(input) != kSamples) throw std::invalid_argument("Unexpected pyannote window size");
    std::vector<float> samples(kSamples);
    jni->GetFloatArrayRegion(input, 0, kSamples, samples.data());
    if (jni->ExceptionCheck()) return nullptr;
    OrtMemoryInfo* memory = nullptr;
    check(api().CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memory));
    std::unique_ptr<OrtMemoryInfo, decltype(api().ReleaseMemoryInfo)> ownedMemory(memory, api().ReleaseMemoryInfo);
    const int64_t shape[] = {1, 1, kSamples};
    OrtValue* tensor = nullptr;
    check(api().CreateTensorWithDataAsOrtValue(memory, samples.data(), samples.size() * sizeof(float),
          shape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tensor));
    std::unique_ptr<OrtValue, decltype(api().ReleaseValue)> ownedTensor(tensor, api().ReleaseValue);
    const char* inputs[] = {"x"};
    const char* outputs[] = {"y"};
    const OrtValue* arguments[] = {tensor};
    OrtValue* output = nullptr;
    check(api().Run(reinterpret_cast<Model*>(handle)->session.get(), nullptr, inputs, arguments, 1, outputs, 1, &output));
    std::unique_ptr<OrtValue, decltype(api().ReleaseValue)> ownedOutput(output, api().ReleaseValue);
    OrtTensorTypeAndShapeInfo* info = nullptr;
    check(api().GetTensorTypeAndShape(output, &info));
    std::unique_ptr<OrtTensorTypeAndShapeInfo, decltype(api().ReleaseTensorTypeAndShapeInfo)> ownedInfo(
        info, api().ReleaseTensorTypeAndShapeInfo);
    size_t rank = 0;
    check(api().GetDimensionsCount(info, &rank));
    if (rank != 3) throw std::runtime_error("Invalid pyannote output rank");
    std::array<int64_t, 3> dims{};
    check(api().GetDimensions(info, dims.data(), dims.size()));
    ONNXTensorElementDataType type;
    check(api().GetTensorElementType(info, &type));
    if (type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || dims[0] != 1 || dims[1] != 589 || dims[2] != kClasses) {
        throw std::runtime_error("Invalid pyannote output tensor");
    }
    void* raw = nullptr;
    check(api().GetTensorMutableData(output, &raw));
    auto* scores = static_cast<float*>(raw);
    std::vector<jbyte> labels(dims[1]);
    for (size_t i = 0; i < labels.size(); ++i) {
        const auto* frame = scores + i * kClasses;
        labels[i] = std::max_element(frame, frame + kClasses) != frame;
    }
    auto result = jni->NewByteArray(static_cast<jsize>(labels.size()));
    if (result) jni->SetByteArrayRegion(result, 0, labels.size(), labels.data());
    return result;
} catch (const std::exception& error) {
    report(jni, error);
    return nullptr;
}
