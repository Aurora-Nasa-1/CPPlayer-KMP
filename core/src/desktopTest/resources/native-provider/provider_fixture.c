#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

JNIEXPORT jstring JNICALL Java_fixture_Factory_nativeTitle(JNIEnv *env, jclass type) {
    return (*env)->NewStringUTF(env, "native");
}

JNIEXPORT jlong JNICALL Java_cp_player_sdk_jni_NativeSession_createNative(
    JNIEnv *env, jobject owner, jstring provider, jstring account, jstring credential, jstring settings
) {
    const char *text = (*env)->GetStringUTFChars(env, account, NULL);
    char *session;
#ifdef _WIN32
    session = _strdup(text);
#else
    session = strdup(text);
#endif
    (*env)->ReleaseStringUTFChars(env, account, text);
    return (jlong)(intptr_t)session;
}

static void write_int(unsigned char **out, int value) {
    int i;
    for (i = 0; i < 4; i++) *(*out)++ = (unsigned char)((unsigned int)value >> (8 * i));
}

static void write_text(unsigned char **out, const char *text) {
    int length = (int)strlen(text);
    write_int(out, length);
    memcpy(*out, text, length);
    *out += length;
}

JNIEXPORT jbyteArray JNICALL Java_cp_player_sdk_jni_NativeSession_invokeNative(
    JNIEnv *env, jobject owner, jlong handle, jint operation, jbyteArray request
) {
    unsigned char buffer[1024], *out = buffer;
    jbyteArray result;
    *out++ = 0;
    if (operation == 1) {
        write_int(&out, 1);
        write_text(&out, "tracks.get");
    } else if (operation == 3) {
        jbyte *input = (*env)->GetByteArrayElements(env, request, NULL);
        unsigned char *bytes = (unsigned char *)input;
        int length = bytes[0] | (bytes[1] << 8) | (bytes[2] << 16) | (bytes[3] << 24);
        write_int(&out, length);
        memcpy(out, bytes + 4, length);
        out += length;
        (*env)->ReleaseByteArrayElements(env, request, input, JNI_ABORT);
        write_text(&out, (const char *)(intptr_t)handle);
        write_int(&out, 0);
        write_int(&out, 9500);
        write_int(&out, 0);
        *out++ = 0;
        *out++ = 0;
    } else {
        buffer[0] = 1;
        write_text(&out, "Unsupported");
        write_text(&out, "Fixture operation is unsupported");
    }
    result = (*env)->NewByteArray(env, (jsize)(out - buffer));
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)(out - buffer), (const jbyte *)buffer);
    return result;
}

JNIEXPORT void JNICALL Java_cp_player_sdk_jni_NativeSession_cancelNative(JNIEnv *env, jobject owner, jlong handle) {}

JNIEXPORT void JNICALL Java_cp_player_sdk_jni_NativeSession_closeNative(JNIEnv *env, jobject owner, jlong handle) {
    free((void *)(intptr_t)handle);
}
