#include <jni.h>
#include <string>
#include "whisper.h"

static struct whisper_context * g_ctx = nullptr;

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_helpagent_MainActivity_stringFromJNI(JNIEnv *env, jobject thiz) {
    std::string hello = "C++ 엔진 브릿지 연결 완료! 드디어 성공!";
    return env->NewStringUTF(hello.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_helpagent_MainActivity_loadWhisperModel(
        JNIEnv *env,
        jobject thiz,
        jstring modelPath_) {

    const char * modelPath = env->GetStringUTFChars(modelPath_, nullptr);

    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    g_ctx = whisper_init_from_file(modelPath);

    env->ReleaseStringUTFChars(modelPath_, modelPath);

    return g_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_helpagent_MainActivity_releaseWhisperModel(
        JNIEnv *env,
        jobject thiz) {

    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_helpagent_MainActivity_transcribeAudio(
        JNIEnv *env,
        jobject thiz,
        jfloatArray samples_) {

    if (g_ctx == nullptr) {
        return env->NewStringUTF("Whisper 모델이 로드되지 않았습니다.");
    }

    jsize n = env->GetArrayLength(samples_);
    jfloat * samples = env->GetFloatArrayElements(samples_, nullptr);

    whisper_full_params params =
            whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.translate = false;
    params.language = "ko";
    params.n_threads = 4;

    int ret = whisper_full(g_ctx, params, samples, n);

    std::string result;
    if (ret != 0) {
        result = "Whisper 추론 실패";
    } else {
        const int n_segments = whisper_full_n_segments(g_ctx);
        for (int i = 0; i < n_segments; ++i) {
            const char * text = whisper_full_get_segment_text(g_ctx, i);
            if (text != nullptr) {
                result += text;
            }
        }

        if (result.empty()) {
            result = "(인식 결과 없음)";
        }
    }

    env->ReleaseFloatArrayElements(samples_, samples, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}