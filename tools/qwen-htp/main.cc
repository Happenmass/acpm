#include "qwen_engine.hpp"

#include <array>
#include <cerrno>
#include <iostream>
#include <limits>
#include <poll.h>
#include <sys/resource.h>
#include <time.h>

namespace {
constexpr size_t kMaxSamples = 24 * 16000;

struct WorkerError {
    const char* detail;
};

int64_t bootTimeMs() {
    timespec now{};
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0) throw WorkerError{"CLOCK_READ_FAILED"};
    return static_cast<int64_t>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
}

bool waitForRequest(int timeoutMs) {
    pollfd input{STDIN_FILENO, POLLIN, 0};
    const int ready = poll(&input, 1, timeoutMs);
    if (ready < 0 && errno != EINTR) throw WorkerError{"PIPE_POLL_FAILED"};
    return ready > 0;
}

long peakRssKiB() {
    rusage usage{};
    if (getrusage(RUSAGE_SELF, &usage) != 0) throw WorkerError{"WORKER_USAGE_FAILED"};
    return usage.ru_maxrss;
}

void serviceState(const char* state) {
    std::ifstream status("/proc/self/status");
    std::string line;
    long rss = 0;
    while (std::getline(status, line)) {
        if (line.starts_with("VmRSS:")) {
            std::istringstream value(line.substr(6));
            value >> rss;
            break;
        }
    }
    std::cout << "SOUND_QWEN_HTP\tstate\t" << state << '\t' << rss << std::endl;
}

std::vector<float> readWav(const std::string& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file) throw WorkerError{"WAV_OPEN_FAILED"};
    std::array<char, 12> header{};
    if (!file.read(header.data(), header.size()) ||
        std::memcmp(header.data(), "RIFF", 4) || std::memcmp(header.data() + 8, "WAVE", 4)) {
        throw WorkerError{"INVALID_RIFF_WAV"};
    }

    bool validFormat = false;
    std::array<char, 4> chunk{};
    uint32_t bytes = 0;
    while (file.read(chunk.data(), chunk.size()).read(reinterpret_cast<char*>(&bytes), sizeof(bytes))) {
        if (!std::memcmp(chunk.data(), "fmt ", 4)) {
            std::array<uint16_t, 8> fmt{};
            if (bytes < sizeof(fmt) || !file.read(reinterpret_cast<char*>(fmt.data()), sizeof(fmt)) ||
                fmt[0] != 1 || fmt[1] != 1 || fmt[2] != 16000 || fmt[3] != 0 || fmt[7] != 16) {
                throw WorkerError{"INVALID_PCM_FORMAT"};
            }
            validFormat = true;
            file.seekg(static_cast<int64_t>(bytes) - sizeof(fmt) + (bytes & 1), std::ios::cur);
        } else if (!std::memcmp(chunk.data(), "data", 4)) {
            if (!validFormat || bytes == 0 || bytes % sizeof(int16_t) || bytes > kMaxSamples * sizeof(int16_t)) {
                throw WorkerError{"INVALID_PCM_LENGTH"};
            }
            std::vector<int16_t> pcm(bytes / sizeof(int16_t));
            if (!file.read(reinterpret_cast<char*>(pcm.data()), bytes)) {
                throw WorkerError{"TRUNCATED_PCM"};
            }
            std::vector<float> samples(pcm.size());
            std::transform(pcm.begin(), pcm.end(), samples.begin(),
                           [](int16_t sample) { return sample / 32768.0f; });
            return samples;
        } else {
            file.seekg(static_cast<int64_t>(bytes) + (bytes & 1), std::ios::cur);
        }
    }
    throw WorkerError{"MISSING_WAV_DATA"};
}

int run(int argc, char** argv, const char*& stage) {
    if (argc == 3 && std::string_view(argv[1]) == "--device-arch") {
        qwen::QnnRuntime runtime(argv[2]);
        const QnnDevice_PlatformInfo_t* platform = nullptr;
        if (!runtime.api.deviceGetInfo ||
            runtime.api.deviceGetInfo(runtime.device, &platform) != QNN_SUCCESS ||
            !platform || platform->version != QNN_DEVICE_PLATFORM_INFO_VERSION_1) return 1;
        for (uint32_t i = 0; i < platform->v1.numHwDevices; ++i) {
            const auto& device = platform->v1.hwDevices[i];
            if (device.version != QNN_DEVICE_HARDWARE_DEVICE_INFO_VERSION_1) continue;
            const auto* ext = device.v1.deviceInfoExtension;
            if (ext && ext->devType == QNN_HTP_DEVICE_TYPE_ON_CHIP) {
                std::cout << "QWEN_HTP_ARCH " << static_cast<int>(ext->onChipDevice.arch) << std::endl;
                return 0;
            }
        }
        return 1;
    }
    const bool emitTokenIds = argc == 5 && std::string_view(argv[4]) == "--token-ids";
    if (argc != 4 && !emitTokenIds) throw WorkerError{"INVALID_START_ARGUMENTS"};
    const auto idleMs = std::stoll(argv[3]);
    if (idleMs <= 0 || idleMs > std::numeric_limits<int>::max()) {
        throw WorkerError{"INVALID_IDLE_TIMEOUT"};
    }
    std::unique_ptr<qwen::Engine> engine;
    int64_t unloadAt = 0;
    std::string request;
    // Clients send one line and wait for its response before sending the next line.
    while (true) {
        stage = "HTP_WORKER_IO";
        const int waitMs = engine ? static_cast<int>(std::max<int64_t>(0, unloadAt - bootTimeMs())) : -1;
        const bool ready = waitForRequest(waitMs);
        if (engine && bootTimeMs() >= unloadAt) {
            engine.reset();
            serviceState("UNLOADED");
        }
        if (!ready) continue;
        if (!std::getline(std::cin, request) || request == "quit") break;
        const auto split = request.find('\t');
        if (split == 0 || split == std::string::npos || request.find_first_not_of("0123456789") != split) {
            throw WorkerError{"INVALID_WORKER_REQUEST"};
        }
        // Older clients send the absolute WAV path immediately after the request ID.
        constexpr std::string_view burstPrefix = "burst\t";
        const std::string_view payload(request.data() + split + 1, request.size() - split - 1);
        const bool burst = payload.starts_with(burstPrefix);
        stage = "HTP_WORKER_WAV";
        const auto samples = readWav(std::string(payload.substr(burst ? burstPrefix.size() : 0)));
        double loadMs = 0;
        if (!engine) {
            stage = "HTP_MODEL_LOAD";
            serviceState("LOADING");
            const auto initialized = std::chrono::steady_clock::now();
            engine = std::make_unique<qwen::Engine>(argv[1], argv[2]);
            loadMs = std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - initialized).count();
        }
        serviceState("RUNNING");
        auto result = engine->transcribe(samples.data(), samples.size(), "auto", stage, 1024, burst);
        if (result.token_limit) throw WorkerError{"KV_CAPACITY_EXHAUSTED"};
        stage = "HTP_WORKER_IO";
        for (char& c : result.text) {
            if (c == '\n' || c == '\r' || c == '\t') c = ' ';
        }
        if (emitTokenIds) {
            std::cout << "SOUND_QWEN_TOKEN_IDS";
            for (const int id : result.tokens) std::cout << '\t' << id;
            std::cout << std::endl;
        }
        const double decodeGraphMs = result.decoder_graph_ms - result.prefill_graph_ms;
        serviceState("WARM");
        std::cout << "SOUND_QWEN_HTP\t" << request.substr(0, split) << '\t'
                  << result.mel_ms << '\t' << result.encoder_ms << '\t'
                  << result.prefill_ms << '\t' << result.decoder_ms << '\t'
                  << result.tokens.size() << '\t' << loadMs << '\t'
                  << decodeGraphMs << '\t' << result.decode_kv_ms << '\t'
                  << result.decode_calls << '\t' << peakRssKiB() << '\t' << result.text << std::endl;
        unloadAt = bootTimeMs() + idleMs;
    }
    return 0;
}
}

int main(int argc, char** argv) {
    const char* stage = "HTP_WORKER_START";
    try {
        return run(argc, argv, stage);
    } catch (const qwen::QnnError& failure) {
        std::cout << "SOUND_QWEN_HTP\terror\t" << stage << '\t' << failure.operation
                  << "\t0x" << std::hex << failure.status << std::endl;
    } catch (const WorkerError& failure) {
        std::cout << "SOUND_QWEN_HTP\terror\t" << stage << '\t' << failure.detail << std::endl;
    } catch (const std::bad_alloc&) {
        std::cout << "SOUND_QWEN_HTP\terror\t" << stage << "\tALLOCATION_FAILED" << std::endl;
    } catch (const std::exception&) {
        std::cout << "SOUND_QWEN_HTP\terror\t" << stage << "\tNATIVE_EXCEPTION" << std::endl;
    }
    return 1;
}
