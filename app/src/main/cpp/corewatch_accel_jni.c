// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <drm/drm.h>
#include <drm/drm_mode.h>

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

struct cw_drm_ivpu_metric_streamer_start {
    uint64_t metric_group_mask;
    uint64_t sampling_period_ns;
    uint32_t read_period_samples;
    uint32_t sample_size;
    uint32_t max_data_size;
};

struct cw_drm_ivpu_metric_streamer_get_data {
    uint64_t metric_group_mask;
    uint64_t buffer_ptr;
    uint64_t buffer_size;
    uint64_t data_size;
};

struct cw_drm_ivpu_metric_streamer_stop {
    uint64_t metric_group_mask;
};

#define CW_DRM_IOCTL_VERSION _IOWR('d', 0x00, struct cw_drm_version)
#define CW_DRM_IOCTL_IVPU_GET_PARAM _IOWR('d', 0x40 + 0x00, struct cw_drm_ivpu_param)
#define CW_DRM_IOCTL_IVPU_METRIC_STREAMER_START _IOWR('d', 0x40 + 0x07, struct cw_drm_ivpu_metric_streamer_start)
#define CW_DRM_IOCTL_IVPU_METRIC_STREAMER_STOP _IOW('d', 0x40 + 0x08, struct cw_drm_ivpu_metric_streamer_stop)
#define CW_DRM_IOCTL_IVPU_METRIC_STREAMER_GET_DATA _IOWR('d', 0x40 + 0x09, struct cw_drm_ivpu_metric_streamer_get_data)

#define CW_DRM_IVPU_PARAM_DEVICE_ID       0
#define CW_DRM_IVPU_PARAM_DEVICE_REVISION 1
#define CW_DRM_IVPU_PARAM_CORE_CLOCK_RATE 3
#define CW_DRM_IVPU_PARAM_NUM_CONTEXTS    4
#define CW_DRM_IVPU_PARAM_CONTEXT_ID      7
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

static pthread_mutex_t g_msMutex = PTHREAD_MUTEX_INITIALIZER;
static int g_msFd = -1;
static uint64_t g_curStreamDpuRaw = 0;
static uint64_t g_curStreamNceRaw = 0;
static int g_idleStreamPolls = 0;
static jlong g_suspendedAtContexts = 0;

static void cw_close_metric_streamer_locked(void) {
    if (g_msFd >= 0) {
        struct cw_drm_ivpu_metric_streamer_stop sp = { .metric_group_mask = 0x4ULL };
        ioctl(g_msFd, CW_DRM_IOCTL_IVPU_METRIC_STREAMER_STOP, &sp);
        close(g_msFd);
        g_msFd = -1;
    }
    g_curStreamDpuRaw = 0;
    g_curStreamNceRaw = 0;
    g_idleStreamPolls = 0;
}

static int cw_drain_metric_streamer_locked(uint64_t *outDpu, uint64_t *outNce) {
    uint64_t freqBuf[512];
    memset(freqBuf, 0, sizeof(freqBuf));
    struct cw_drm_ivpu_metric_streamer_get_data gd;
    memset(&gd, 0, sizeof(gd));
    gd.metric_group_mask = 0x4ULL;
    gd.buffer_ptr = (uint64_t)(uintptr_t)freqBuf;
    gd.buffer_size = sizeof(freqBuf);
    if (ioctl(g_msFd, CW_DRM_IOCTL_IVPU_METRIC_STREAMER_GET_DATA, &gd) != 0) {
        cw_close_metric_streamer_locked();
        return 0;
    }

    uint64_t intervalPeakDpu = 0;
    uint64_t intervalPeakNce = 0;
    if (gd.data_size >= 24) {
        size_t totalU64 = (size_t)(gd.data_size / 8);
        if (totalU64 > 512) totalU64 = 512;
        for (size_t base = 0; base + 2 < totalU64; base += 3) {
            uint64_t dpu = freqBuf[base];
            uint64_t nce = freqBuf[base + 1];
            if (dpu >= intervalPeakDpu) {
                intervalPeakDpu = dpu;
                intervalPeakNce = nce;
            }
            g_curStreamDpuRaw = dpu;
            g_curStreamNceRaw = nce;
        }
    }

    *outDpu = (intervalPeakDpu > 0) ? intervalPeakDpu : g_curStreamDpuRaw;
    *outNce = (intervalPeakDpu > 0) ? intervalPeakNce : g_curStreamNceRaw;
    return 1;
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

    char pathBuf[128];
    snprintf(pathBuf, sizeof(pathBuf), "%s", path);
    (*env)->ReleaseStringUTFChars(env, pathJstr, path);

    int fd = open(pathBuf, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        pthread_mutex_lock(&g_msMutex);
        cw_close_metric_streamer_locked();
        pthread_mutex_unlock(&g_msMutex);
        return NULL;
    }

    jlong out[11];
    memset(out, 0, sizeof(out));
    out[8] = -1;
    out[9] = -1;
    out[10] = 0;

    pthread_mutex_lock(&g_msMutex);
    struct cw_drm_ivpu_param ctx = { .param = CW_DRM_IVPU_PARAM_CONTEXT_ID, .index = 0, .value = 0 };
    int ctxOk = (ioctl(fd, CW_DRM_IOCTL_IVPU_GET_PARAM, &ctx) == 0);
    /* Subtract our probe fd (2) plus our persistent metric streamer fd (1) if currently open. */
    uint64_t selfContextBase = (g_msFd >= 0) ? 3ULL : 2ULL;
    jlong activeContexts = 0;
    if (ctxOk && ctx.value >= selfContextBase) {
        activeContexts = (jlong)(ctx.value - selfContextBase);
    }
    out[7] = activeContexts;

    if (ctxOk) {
        if (activeContexts == 0) {
            /* Zero external NPU client contexts: close streamer if open so NPU sleeps in D3cold (0 MHz). */
            cw_close_metric_streamer_locked();
            g_suspendedAtContexts = 0;
            out[8] = 0;
            out[9] = 0;
            out[10] = 1;
        } else if (g_msFd < 0 && g_suspendedAtContexts > 0 && activeContexts == g_suspendedAtContexts) {
            /* Leaked idle client context already went quiescent; avoid holding NPU runtime PM awake. */
            out[8] = 0;
            out[9] = 0;
            out[10] = 1;
        } else {
            g_suspendedAtContexts = 0;
            if (g_msFd < 0) {
                g_msFd = open(pathBuf, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
                if (g_msFd >= 0) {
                    struct cw_drm_ivpu_metric_streamer_start st;
                    memset(&st, 0, sizeof(st));
                    st.metric_group_mask = 0x4ULL;
                    st.sampling_period_ns = 4000000ULL;
                    st.read_period_samples = 1;
                    if (ioctl(g_msFd, CW_DRM_IOCTL_IVPU_METRIC_STREAMER_START, &st) == 0) {
                        g_curStreamDpuRaw = 0;
                        g_curStreamNceRaw = 0;
                        g_idleStreamPolls = 0;
                        usleep(45000); /* Initial 45 ms window on 0->1 context transition */
                    } else {
                        close(g_msFd);
                        g_msFd = -1;
                    }
                }
            }
            if (g_msFd >= 0) {
                uint64_t dpuRaw = 0;
                uint64_t nceRaw = 0;
                if (cw_drain_metric_streamer_locked(&dpuRaw, &nceRaw)) {
                    out[8] = (jlong)dpuRaw;
                    out[9] = (jlong)nceRaw;
                    out[10] = 1;
                    if (dpuRaw == 0) {
                        g_idleStreamPolls++;
                        if (g_idleStreamPolls >= 3) {
                            cw_close_metric_streamer_locked();
                            g_suspendedAtContexts = activeContexts;
                        }
                    } else {
                        g_idleStreamPolls = 0;
                    }
                }
            }
        }
    }
    pthread_mutex_unlock(&g_msMutex);

    if (heartbeatOnly) {
        if (activeContexts > 0) {
            struct cw_drm_ivpu_param hb = { .param = CW_DRM_IVPU_PARAM_ENGINE_HEARTBEAT, .index = 0, .value = 0 };
            if (ioctl(fd, CW_DRM_IOCTL_IVPU_GET_PARAM, &hb) == 0) {
                out[5] = (jlong)hb.value;
            }
        }
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
            if (params[i] == CW_DRM_IVPU_PARAM_ENGINE_HEARTBEAT && activeContexts == 0) {
                continue;
            }
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
    jlongArray arr = (*env)->NewLongArray(env, 11);
    if (arr != NULL) {
        (*env)->SetLongArrayRegion(env, arr, 0, 11, out);
    }
    return arr;
}

static const char *cw_connector_type_name(uint32_t type) {
    switch (type) {
        case 11: return "HDMI-A";
        case 12: return "HDMI-B";
        case 10: return "DP";
        case 14: return "eDP";
        case 15: return "Virtual";
        case 16: return "DSI";
        case 19: return "USB";
        default: return "Display";
    }
}

JNIEXPORT jstring JNICALL
Java_com_droid_corewatch_data_AccelIoctlBridge_nativeQueryDrmConnectors(
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

    struct drm_mode_card_res res;
    memset(&res, 0, sizeof(res));
    if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &res) != 0 || res.count_connectors == 0) {
        close(fd);
        return NULL;
    }

    uint32_t conn_ids[32] = {0};
    uint32_t crtc_ids[32] = {0};
    uint32_t fb_ids[32] = {0};
    uint32_t enc_ids[32] = {0};
    if (res.count_connectors > 32) res.count_connectors = 32;
    if (res.count_crtcs > 32) res.count_crtcs = 32;
    if (res.count_fbs > 32) res.count_fbs = 32;
    if (res.count_encoders > 32) res.count_encoders = 32;
    res.connector_id_ptr = (uint64_t)(uintptr_t)conn_ids;
    res.crtc_id_ptr = (uint64_t)(uintptr_t)crtc_ids;
    res.fb_id_ptr = (uint64_t)(uintptr_t)fb_ids;
    res.encoder_id_ptr = (uint64_t)(uintptr_t)enc_ids;

    if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &res) != 0) {
        close(fd);
        return NULL;
    }

    int anyCrtcVrrEnabled = 0;
    for (uint32_t i = 0; i < res.count_crtcs && i < 32; i++) {
        struct drm_mode_obj_get_properties props;
        memset(&props, 0, sizeof(props));
        props.obj_id = crtc_ids[i];
        props.obj_type = DRM_MODE_OBJECT_CRTC;
        if (ioctl(fd, DRM_IOCTL_MODE_OBJ_GETPROPERTIES, &props) == 0 && props.count_props > 0) {
            uint32_t pids[64] = {0};
            uint64_t pvals[64] = {0};
            if (props.count_props > 64) props.count_props = 64;
            props.props_ptr = (uint64_t)(uintptr_t)pids;
            props.prop_values_ptr = (uint64_t)(uintptr_t)pvals;
            if (ioctl(fd, DRM_IOCTL_MODE_OBJ_GETPROPERTIES, &props) == 0) {
                for (uint32_t j = 0; j < props.count_props; j++) {
                    struct drm_mode_get_property pr;
                    memset(&pr, 0, sizeof(pr));
                    pr.prop_id = pids[j];
                    if (ioctl(fd, DRM_IOCTL_MODE_GETPROPERTY, &pr) == 0) {
                        if (strcmp(pr.name, "VRR_ENABLED") == 0 && pvals[j] != 0) {
                            anyCrtcVrrEnabled = 1;
                        }
                    }
                }
            }
        }
    }

    char outBuf[512];
    outBuf[0] = '\0';
    size_t outLen = 0;

    for (uint32_t i = 0; i < res.count_connectors && i < 32; i++) {
        struct drm_mode_get_connector conn;
        memset(&conn, 0, sizeof(conn));
        conn.connector_id = conn_ids[i];
        if (ioctl(fd, DRM_IOCTL_MODE_GETCONNECTOR, &conn) != 0) continue;
        if (conn.connection != 1) continue; // 1 == DRM_MODE_CONNECTED

        int vrrCapable = 0;
        int maxBpc = 8;

        struct drm_mode_obj_get_properties props;
        memset(&props, 0, sizeof(props));
        props.obj_id = conn_ids[i];
        props.obj_type = DRM_MODE_OBJECT_CONNECTOR;
        if (ioctl(fd, DRM_IOCTL_MODE_OBJ_GETPROPERTIES, &props) == 0 && props.count_props > 0) {
            uint32_t pids[64] = {0};
            uint64_t pvals[64] = {0};
            if (props.count_props > 64) props.count_props = 64;
            props.props_ptr = (uint64_t)(uintptr_t)pids;
            props.prop_values_ptr = (uint64_t)(uintptr_t)pvals;
            if (ioctl(fd, DRM_IOCTL_MODE_OBJ_GETPROPERTIES, &props) == 0) {
                for (uint32_t j = 0; j < props.count_props; j++) {
                    struct drm_mode_get_property pr;
                    memset(&pr, 0, sizeof(pr));
                    pr.prop_id = pids[j];
                    if (ioctl(fd, DRM_IOCTL_MODE_GETPROPERTY, &pr) == 0) {
                        if (strcmp(pr.name, "vrr_capable") == 0) {
                            vrrCapable = (pvals[j] != 0) ? 1 : 0;
                        } else if (strcmp(pr.name, "max bpc") == 0 && pvals[j] >= 6 && pvals[j] <= 16) {
                            maxBpc = (int)pvals[j];
                        }
                    }
                }
            }
        }

        char entry[128];
        int n = snprintf(
            entry,
            sizeof(entry),
            "%s%s-%u|%d|%d|%d|%ux%u",
            (outLen > 0) ? ";" : "",
            cw_connector_type_name(conn.connector_type),
            conn.connector_type_id,
            vrrCapable,
            anyCrtcVrrEnabled,
            maxBpc,
            conn.mm_width,
            conn.mm_height
        );
        if (n > 0 && outLen + (size_t)n < sizeof(outBuf) - 1) {
            memcpy(outBuf + outLen, entry, (size_t)n);
            outLen += (size_t)n;
            outBuf[outLen] = '\0';
        }
    }

    close(fd);
    if (outLen == 0) return NULL;
    return (*env)->NewStringUTF(env, outBuf);
}
