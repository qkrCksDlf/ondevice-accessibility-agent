#include <jni.h>
#include <string>


extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_helpagent_MainActivity_stringFromJNI(JNIEnv *env, jobject thiz) {
    std::string hello = "C++ 엔진 브릿지 연결 완료! 드디어 성공!";
    return env->NewStringUTF(hello.c_str());
}