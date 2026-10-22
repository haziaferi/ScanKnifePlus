// JNI shim for the OCR benchmark's field mode: Tesseract4Android 4.10.1 does not expose orientation and script detection to Java, but its
// libtesseract.so exports Tesseract's C API. This calls TessBaseAPIDetectOrientationScript on the TessBaseAPI that a Java TessBaseAPI wraps
// (its mNativeData points at a struct whose first member is the tesseract::TessBaseAPI). Built by docs/ocr-bench/run.sh --field with the
// NDK into the git-ignored app/src/androidTest/jniLibs/, so it reaches the test APK only.
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>

typedef int (*detect_fn)(void *api, int *orient_deg, float *orient_conf, const char **script_name, float *script_conf);

// Returns "<orient_deg>\t<orient_conf>\t<script>\t<script_conf>", or "error\t<reason>". orient_deg is Tesseract's value (orientation id * 90).
JNIEXPORT jstring JNICALL Java_com_haziaferi_scanknifeplus_ocr_TessOsd_nativeDetect(JNIEnv *env, jclass cls, jlong api) {
    (void) cls;
    char out[256];
    void *lib = dlopen("libtesseract.so", RTLD_NOW | RTLD_NOLOAD);
    if (lib == NULL) lib = dlopen("libtesseract.so", RTLD_NOW);
    detect_fn detect = lib == NULL ? NULL : (detect_fn) dlsym(lib, "TessBaseAPIDetectOrientationScript");
    if (detect == NULL) {
        snprintf(out, sizeof out, "error\tno TessBaseAPIDetectOrientationScript: %s", dlerror());
        return (*env)->NewStringUTF(env, out);
    }
    int deg = -1;
    float conf = 0, script_conf = 0;
    const char *script = NULL;
    if (!detect((void *) (intptr_t) api, &deg, &conf, &script, &script_conf)) {
        snprintf(out, sizeof out, "error\tdetection failed (too few characters?)");
    } else {
        snprintf(out, sizeof out, "%d\t%.2f\t%s\t%.2f", deg, conf, script == NULL ? "?" : script, script_conf);
    }
    return (*env)->NewStringUTF(env, out);
}
