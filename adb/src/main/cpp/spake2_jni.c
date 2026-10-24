#include <stdint.h>
#include <jni.h>
#include <spake2/spake2.h>

static struct spake2_ctx_st *context(jlong value) { return (struct spake2_ctx_st *)(intptr_t)value; }
static jbyteArray bytes(JNIEnv *env, uint8_t *value, size_t size) {
    jbyteArray result = (*env)->NewByteArray(env, (jsize)size);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)size, (jbyte *)value);
    volatile uint8_t *clear = value;
    for (size_t i = 0; i < size; ++i) clear[i] = 0;
    return result;
}
JNIEXPORT jlong JNICALL Java_io_github_muntashirakon_crypto_spake2_Spake2Context_allocate(
        JNIEnv *env, jclass type, jint role, jbyteArray mine, jbyteArray theirs) {
    uint8_t own[1024], peer[1024];
    jsize ownSize = (*env)->GetArrayLength(env, mine), peerSize = (*env)->GetArrayLength(env, theirs);
    if (ownSize < 1 || ownSize > 1024 || peerSize < 1 || peerSize > 1024) return 0;
    (*env)->GetByteArrayRegion(env, mine, 0, ownSize, (jbyte *)own);
    (*env)->GetByteArrayRegion(env, theirs, 0, peerSize, (jbyte *)peer);
    if ((*env)->ExceptionCheck(env)) return 0;
    return (jlong)(intptr_t)SPAKE2_CTX_new(role == 0 ? spake2_role_alice : spake2_role_bob, own, ownSize, peer, peerSize);
}
JNIEXPORT jbyteArray JNICALL Java_io_github_muntashirakon_crypto_spake2_Spake2Context_generate(
        JNIEnv *env, jclass type, jlong handle, jbyteArray password, jbyteArray entropy, jboolean legacy) {
    uint8_t pass[4096], random[64], output[32];
    jsize size = (*env)->GetArrayLength(env, password);
    if (!handle || size > 4096 || (*env)->GetArrayLength(env, entropy) != 64) return NULL;
    (*env)->GetByteArrayRegion(env, password, 0, size, (jbyte *)pass);
    (*env)->GetByteArrayRegion(env, entropy, 0, 64, (jbyte *)random);
    if ((*env)->ExceptionCheck(env)) return NULL;
    context(handle)->disable_password_scalar_hack = legacy ? 1 : 0;
    size_t outSize = 0;
    int valid = SPAKE2_generate_msg(context(handle), output, &outSize, sizeof(output), pass, size, random);
    volatile uint8_t *clear = pass;
    for (int i = 0; i < size; ++i) clear[i] = 0;
    clear = random;
    for (int i = 0; i < 64; ++i) clear[i] = 0;
    if (!valid || outSize != 32) return NULL;
    return bytes(env, output, outSize);
}
JNIEXPORT jbyteArray JNICALL Java_io_github_muntashirakon_crypto_spake2_Spake2Context_process(
        JNIEnv *env, jclass type, jlong handle, jbyteArray peer) {
    uint8_t input[32], output[64];
    if (!handle || (*env)->GetArrayLength(env, peer) != 32) return NULL;
    (*env)->GetByteArrayRegion(env, peer, 0, 32, (jbyte *)input);
    if ((*env)->ExceptionCheck(env)) return NULL;
    size_t size = 0;
    if (!SPAKE2_process_msg(context(handle), output, &size, sizeof(output), input, sizeof(input)) || size != 64) return NULL;
    return bytes(env, output, size);
}
JNIEXPORT void JNICALL Java_io_github_muntashirakon_crypto_spake2_Spake2Context_release(
        JNIEnv *env, jclass type, jlong handle) { SPAKE2_CTX_free(context(handle)); }
