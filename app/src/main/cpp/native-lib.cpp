#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <chrono>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "native-lib"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::mutex g_mutex;

    bool g_backend_initialized = false;
    llama_model * g_model = nullptr;
    llama_context * g_ctx = nullptr;
    llama_sampler * g_sampler = nullptr;

    // ✨ 시스템 프롬프트 캐시
    int g_system_prompt_n = 0;
    bool g_system_cached = false;
    size_t g_cached_system_hash = 0;

    constexpr int DEFAULT_N_CTX = 2048;
    constexpr int DEFAULT_N_BATCH = 512;
    constexpr int DEFAULT_MAX_TOKENS = 512;
    constexpr int DEFAULT_THREADS = 6;

    void cleanup_locked() {
        if (g_sampler) {
            llama_sampler_free(g_sampler);
            g_sampler = nullptr;
        }
        if (g_ctx) {
            llama_free(g_ctx);
            g_ctx = nullptr;
        }
        if (g_model) {
            llama_model_free(g_model);
            g_model = nullptr;
        }
        if (g_backend_initialized) {
            llama_backend_free();
            g_backend_initialized = false;
        }
        g_system_prompt_n = 0;
        g_system_cached = false;
        g_cached_system_hash = 0;
    }

    std::string jstringToStdString(JNIEnv *env, jstring jStr) {
        if (!jStr) return "";
        const char *chars = env->GetStringUTFChars(jStr, nullptr);
        std::string result = chars ? chars : "";
        if (chars) env->ReleaseStringUTFChars(jStr, chars);
        return result;
    }

    std::vector<llama_token> tokenize(const llama_model * model, const std::string & text, bool add_special, bool parse_special) {
        const llama_vocab * vocab = llama_model_get_vocab(model);

        int n_tokens = -llama_tokenize(
                vocab,
                text.c_str(),
                (int32_t) text.size(),
                nullptr,
                0,
                add_special,
                parse_special
        );

        if (n_tokens <= 0) return {};

        std::vector<llama_token> tokens(n_tokens);
        int result = llama_tokenize(
                vocab,
                text.c_str(),
                (int32_t) text.size(),
                tokens.data(),
                (int32_t) tokens.size(),
                add_special,
                parse_special
        );

        if (result < 0) {
            LOGE("tokenize failed");
            return {};
        }

        tokens.resize(result);
        return tokens;
    }

    std::string token_to_piece_string(const llama_model * model, llama_token token) {
        const llama_vocab * vocab = llama_model_get_vocab(model);

        std::vector<char> piece(16, 0);
        int n_chars = llama_token_to_piece(vocab, token, piece.data(), (int32_t) piece.size(), 0, true);

        if (n_chars < 0) {
            piece.resize(-n_chars);
            n_chars = llama_token_to_piece(vocab, token, piece.data(), (int32_t) piece.size(), 0, true);
        }

        if (n_chars < 0) return "";
        return std::string(piece.data(), n_chars);
    }

    llama_sampler * create_sampler() {
        auto sparams = llama_sampler_chain_default_params();
        sparams.no_perf = false;
        llama_sampler * sampler = llama_sampler_chain_init(sparams);
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
        return sampler;
    }

    // ✨ 최적화 적용된 컨텍스트 생성
    llama_context * create_context(llama_model * model) {
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx        = DEFAULT_N_CTX;
        ctx_params.n_batch      = DEFAULT_N_BATCH;
        ctx_params.n_ubatch     = DEFAULT_N_BATCH;
        ctx_params.no_perf      = false;
        ctx_params.n_threads    = DEFAULT_THREADS;
        ctx_params.n_threads_batch = DEFAULT_THREADS;

        // ✨ KV Cache Q8 양자화 (메모리 절반)
        ctx_params.type_k = GGML_TYPE_Q8_0;
        ctx_params.type_v = GGML_TYPE_Q8_0;

        // ✨ Flash Attention
        ctx_params.flash_attn = true;

        return llama_init_from_model(model, ctx_params);
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_helpagent_MainActivity_loadLlamaModel(
        JNIEnv *env,
        jobject /* thiz */,
        jstring modelPath_) {

    std::lock_guard<std::mutex> lock(g_mutex);

    std::string modelPath = jstringToStdString(env, modelPath_);
    if (modelPath.empty()) {
        LOGE("model path is empty");
        return JNI_FALSE;
    }

    cleanup_locked();

    llama_log_set([](ggml_log_level level, const char* text, void* /*user_data*/) {
        int prio = ANDROID_LOG_INFO;
        if (level == GGML_LOG_LEVEL_ERROR)      prio = ANDROID_LOG_ERROR;
        else if (level == GGML_LOG_LEVEL_WARN)  prio = ANDROID_LOG_WARN;
        else if (level == GGML_LOG_LEVEL_DEBUG) prio = ANDROID_LOG_DEBUG;
        __android_log_print(prio, "llama.cpp", "%s", text);
    }, nullptr);

    llama_backend_init();
    g_backend_initialized = true;

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;   // CPU 전용 (측정 결과 GPU 효과 미미)
    model_params.use_mmap     = true;
    model_params.use_mlock    = false;

    g_model = llama_model_load_from_file(modelPath.c_str(), model_params);
    if (!g_model) {
        LOGE("failed to load model: %s", modelPath.c_str());
        cleanup_locked();
        return JNI_FALSE;
    }

    // ✨ 컨텍스트와 샘플러는 한 번만 생성
    g_ctx = create_context(g_model);
    if (!g_ctx) {
        LOGE("failed to create context");
        cleanup_locked();
        return JNI_FALSE;
    }

    g_sampler = create_sampler();
    if (!g_sampler) {
        LOGE("failed to create sampler");
        cleanup_locked();
        return JNI_FALSE;
    }

    char desc[256];
    int desc_len = llama_model_desc(g_model, desc, sizeof(desc));
    if (desc_len > 0) LOGI("model loaded: %s", desc);
    else LOGI("model loaded");
    LOGI("config: n_ctx=%d, batch=%d, n_gpu_layers=%d, flash_attn=true, kv_cache=Q8_0",
         DEFAULT_N_CTX, DEFAULT_N_BATCH, model_params.n_gpu_layers);

    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_helpagent_MainActivity_generateLlamaResponse(
        JNIEnv *env,
        jobject /* thiz */,
        jstring prompt_) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx || !g_sampler) {
        return env->NewStringUTF("모델이 아직 로드되지 않았어요.");
    }

    std::string prompt = jstringToStdString(env, prompt_);
    if (prompt.empty()) {
        return env->NewStringUTF("입력이 비어 있어요.");
    }

    // ✨ 시스템 프롬프트와 사용자 메시지 분리
    const std::string user_marker = "<|start_header_id|>user<|end_header_id|>";
    size_t user_pos = prompt.find(user_marker);

    std::string system_part;
    std::string user_part;

    if (user_pos != std::string::npos) {
        system_part = prompt.substr(0, user_pos);
        user_part   = prompt.substr(user_pos);
    } else {
        // marker 없으면 전체를 사용자 메시지로 처리
        system_part = "";
        user_part   = prompt;
    }

    // ✨ prefill 처리 (마지막 '{' 이후 분리)
    size_t prefill_pos = user_part.rfind('{');
    std::string user_body  = (prefill_pos != std::string::npos) ? user_part.substr(0, prefill_pos) : user_part;
    bool has_prefill        = (prefill_pos != std::string::npos);

    auto t_prompt_start = std::chrono::high_resolution_clock::now();

    int n_past = 0;
    int n_prompt_tokens_total = 0;

    // ✨ 시스템 프롬프트 해시 비교 (변경 감지)
    size_t current_hash = std::hash<std::string>{}(system_part);
    bool system_changed = (g_system_cached && current_hash != g_cached_system_hash);

    if (system_changed) {
        LOGI("⚠️ System prompt changed, invalidating cache");
        g_system_cached = false;
        g_system_prompt_n = 0;
        llama_kv_cache_clear(g_ctx);
    }

    // ✨ 시스템 프롬프트 처리 (캐싱 또는 새로 처리)
    if (!g_system_cached && !system_part.empty()) {
        // 첫 요청 또는 시스템 변경 시: 시스템 프롬프트 처리
        llama_kv_cache_clear(g_ctx);

        std::vector<llama_token> sys_tokens = tokenize(g_model, system_part, true, true);
        if (sys_tokens.empty()) {
            return env->NewStringUTF("시스템 프롬프트 토큰화 실패");
        }

        if ((int)sys_tokens.size() >= DEFAULT_N_CTX - 256) {
            return env->NewStringUTF("시스템 프롬프트가 너무 깁니다.");
        }

        llama_batch sys_batch = llama_batch_get_one(sys_tokens.data(), (int32_t) sys_tokens.size());
        if (llama_decode(g_ctx, sys_batch) != 0) {
            return env->NewStringUTF("시스템 프롬프트 처리 실패");
        }

        g_system_prompt_n = (int) sys_tokens.size();
        g_system_cached = true;
        g_cached_system_hash = current_hash;
        n_past = g_system_prompt_n;
        n_prompt_tokens_total = g_system_prompt_n;
        LOGI("✨ System prompt cached: %d tokens", g_system_prompt_n);
    } else if (g_system_cached) {
        // 캐시 hit: 시스템 부분 보존, 이전 사용자 부분만 제거
        llama_kv_cache_seq_rm(g_ctx, 0, g_system_prompt_n, -1);
        n_past = g_system_prompt_n;
        LOGI("✨ Using cached system prompt (%d tokens)", g_system_prompt_n);
    } else {
        // 시스템 프롬프트 없는 경우
        llama_kv_cache_clear(g_ctx);
        n_past = 0;
    }

    // ✨ 사용자 메시지 부분 토큰화 (BOS 자동 추가 안 함, 시스템에서 이미 추가됨)
    std::vector<llama_token> user_tokens = tokenize(g_model, user_body, false, true);
    if (user_tokens.empty()) {
        return env->NewStringUTF("프롬프트 토큰화 실패");
    }

    // prefill 토큰 추가
    if (has_prefill) {
        std::vector<llama_token> prefill_tokens = tokenize(g_model, "{", false, false);
        user_tokens.insert(user_tokens.end(), prefill_tokens.begin(), prefill_tokens.end());
    }

    int n_user_tokens = (int) user_tokens.size();
    n_prompt_tokens_total += n_user_tokens;

    if (n_past + n_user_tokens >= DEFAULT_N_CTX - 1) {
        return env->NewStringUTF("입력이 너무 길어요. 더 짧게 입력해 주세요.");
    }

    // 사용자 메시지 처리
    llama_batch user_batch = llama_batch_get_one(user_tokens.data(), n_user_tokens);
    if (llama_decode(g_ctx, user_batch) != 0) {
        return env->NewStringUTF("사용자 메시지 처리 실패");
    }
    n_past += n_user_tokens;

    auto t_prompt_end = std::chrono::high_resolution_clock::now();
    auto prompt_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_prompt_end - t_prompt_start).count();

    // 샘플러 리셋 (그리디는 무상태지만 안전을 위해)
    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = create_sampler();
        if (!g_sampler) return env->NewStringUTF("샘플러 초기화 실패");
    }

    const llama_vocab * vocab = llama_model_get_vocab(g_model);

    std::string output = has_prefill ? "{" : "";
    output.reserve(512);

    bool json_closed = false;
    int n_generated = 0;

    auto t_gen_start = std::chrono::high_resolution_clock::now();

    for (int i = 0; i < DEFAULT_MAX_TOKENS; ++i) {
        llama_token new_token_id = llama_sampler_sample(g_sampler, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token_id)) break;

        std::string piece = token_to_piece_string(g_model, new_token_id);
        output += piece;
        n_generated++;

        if (output.find('}') != std::string::npos) {
            json_closed = true;
            break;
        }

        llama_batch batch = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(g_ctx, batch) != 0) break;
        n_past++;
    }

    auto t_gen_end = std::chrono::high_resolution_clock::now();
    auto gen_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_gen_end - t_gen_start).count();

    double prompt_tps = (n_prompt_tokens_total * 1000.0) / (prompt_ms > 0 ? prompt_ms : 1);
    double gen_tps    = (n_generated * 1000.0) / (gen_ms > 0 ? gen_ms : 1);

    bool used_cache = g_system_cached && !system_changed && g_system_prompt_n > 0;

    LOGI("=========================================");
    LOGI("⏱️  PERFORMANCE METRICS");
    LOGI("Prompt: %d tokens in %lld ms = %.2f tok/s (cache: %s)",
         n_prompt_tokens_total, (long long)prompt_ms, prompt_tps,
         used_cache ? "HIT" : "MISS");
    LOGI("Gen:    %d tokens in %lld ms = %.2f tok/s",
         n_generated, (long long)gen_ms, gen_tps);
    LOGI("=========================================");

    if (!json_closed && !output.empty()) {
        output += "}";
    }

    if (output.empty() || output == "{}" ) {
        output = "{\"intent\":\"chat\",\"query\":\"\",\"msg\":\"죄송해요, 다시 한 번 말씀해 주세요.\"}";
    }

    LOGI("LLM output: %s", output.c_str());

    return env->NewStringUTF(output.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_helpagent_MainActivity_releaseLlamaModel(
        JNIEnv * /* env */,
        jobject /* thiz */) {

    std::lock_guard<std::mutex> lock(g_mutex);
    cleanup_locked();
}