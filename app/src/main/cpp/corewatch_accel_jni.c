// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>

struct cw_drm_version {
    int version_major;
    int version_minor;
    int version_patchlevel;
    size_t name_len;
    char *name;
    size_t date_len;
    char *date;
    size_t desc_len;
    char *desc;
};

struct cw_drm_ivpu_param {
    uint32_t param;
    uint32_t index;
    uint64_t value;
};

#define CW_DRM_IOCTL_VERSION _IOWR('d', 0x00, struct cw_drm_version)
#define CW_DRM_IOCTL_IVPU_GET_PARAM _IOWR('d', 0x40 + 0x00, struct cw_drm_ivpu_param)

#define CW_DRM_IVPU_PARAM_DEVICE_ID       0
#define CW_DRM_IVPU_PARAM_DEVICE_REVISION 1
#define CW_DRM_IVPU_PARAM_CORE_CLOCK_RATE 3
#define CW_DRM_IVPU_PARAM_NUM_CONTEXTS    4
#define CW_DRM_IVPU_PARAM_FW_API_VERSION  8
#define CW_DRM_IVPU_PARAM_ENGINE_HEARTBEAT 9
#define CW_DRM_IVPU_PARAM_TILE_CONFIG     11

JNIEXPORT jstring JNICALL
Java_com_droid_corewatch_data_AccelIoctlBridge_nativeQueryDrmVersion(
    JNIEnv *env,
    jobject thiz,
    jstring pathJstr
) {
    (void)thiz;
    if (pathJstr == NULL) return NULL;
    const char *path = (*env)->GetStringUTFChars(env, pathJstr, NULL);
    if (path == NULL) return NULL;

    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, pathJstr, path);
    if (fd < 0) {
        return NULL;
    }

    char name[64];
    char date[64];
    char desc[128];
    memset(name, 0, sizeof(name));
    memset(date, 0, sizeof(date));
    memset(desc, 0, sizeof(desc));

    struct cw_drm_version ver;
    memset(&ver, 0, sizeof(ver));
    ver.name_len = sizeof(name) - 1;
    ver.name = name;
    ver.date_len = sizeof(date) - 1;
    ver.date = date;
    ver.desc_len = sizeof(desc) - 1;
    ver.desc = desc;

    int rc = ioctl(fd, CW_DRM_IOCTL_VERSION, &ver);
    close(fd);
    if (rc != 0) {
        return NULL;
    }

    char formatted[256];
    snprintf(
        formatted,
        sizeof(formatted),
        "%s|%s|%d.%d.%d",
        name,
        desc,
        ver.version_major,
        ver.version_minor,
        ver.version_patchlevel
    );
    return (*env)->NewStringUTF(env, formatted);
}

JNIEXPORT jlongArray JNICALL
Java_com_droid_corewatch_data_AccelIoctlBridge_nativeQueryIvpuParams(
    JNIEnv *env,
    jobject thiz,
    jstring pathJstr,
    jboolean heartbeatOnly
) {
    (void)thiz;
    if (pathJstr == NULL) return NULL;
    const char *path = (*env)->GetStringUTFChars(env, pathJstr, NULL);
    if (path == NULL) return NULL;

    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, pathJstr, path);
    if (fd < 0) {
        return NULL;
    }

    jlong out[7];
    memset(out, 0, sizeof(out));

    if (heartbeatOnly) {
        struct cw_drm_ivpu_param hb = { .param = CW_DRM_IVPU_PARAM_ENGINE_HEARTBEAT, .index = 0, .value = 0 };
        if (ioctl(fd, CW_DRM_IOCTL_IVPU_GET_PARAM, &hb) != 0) {
            close(fd);
            return NULL;
        }
        out[5] = (jlong)hb.value;
    } else {
        const uint32_t params[7] = {
            CW_DRM_IVPU_PARAM_DEVICE_ID,
            CW_DRM_IVPU_PARAM_DEVICE_REVISION,
            CW_DRM_IVPU_PARAM_CORE_CLOCK_RATE,
            CW_DRM_IVPU_PARAM_NUM_CONTEXTS,
            CW_DRM_IVPU_PARAM_FW_API_VERSION,
            CW_DRM_IVPU_PARAM_ENGINE_HEARTBEAT,
            CW_DRM_IVPU_PARAM_TILE_CONFIG
        };
        int anyOk = 0;
        for (int i = 0; i < 7; i++) {
            struct cw_drm_ivpu_param arg = { .param = params[i], .index = 0, .value = 0 };
            if (ioctl(fd, CW_DRM_IOCTL_IVPU_GET_PARAM, &arg) == 0) {
                out[i] = (jlong)arg.value;
                anyOk = 1;
            }
        }
        if (!anyOk) {
            close(fd);
            return NULL;
        }
    }

    close(fd);
    jlongArray arr = (*env)->NewLongArray(env, 7);
    if (arr != NULL) {
        (*env)->SetLongArrayRegion(env, arr, 0, 7, out);
    }
    return arr;
}
