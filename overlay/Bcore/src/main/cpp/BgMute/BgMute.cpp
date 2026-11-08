// Mutes the native audio of a virtual app while it is in the background.
//
// Games usually play sound through OpenSL ES or AAudio, which end up in android::AudioTrack
// (libaudioclient.so). Java players cannot be reached from here, StayActive handles those. This
// library hooks AudioTrack::setVolume (both overloads), AudioTrack::start and the destructors:
//  - it remembers every track and the volume the app asked for,
//  - while muted, it passes 0 to the real setVolume instead,
//  - nativeSetMuted() re-applies the volume to every live track right away.
// If a symbol cannot be found the hook for it is skipped; nothing here can make a track fail.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <map>
#include <mutex>
#include <utility>

#include "Dobby/dobby.h"
#include "xdl.h"

#define TAG "BgMute"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

namespace {

typedef int (*SetVolume2Fn)(void *, float, float);
typedef int (*SetVolume1Fn)(void *, float);
typedef int (*StartFn)(void *);
typedef void (*DtorFn)(void *);

SetVolume2Fn orig_setVolume2 = nullptr;
SetVolume1Fn orig_setVolume1 = nullptr;
StartFn orig_start = nullptr;
DtorFn orig_dtor0 = nullptr;
DtorFn orig_dtor1 = nullptr;

std::atomic<bool> g_muted(false);
std::mutex g_lock;
std::map<void *, std::pair<float, float>> g_tracks;  // track -> volume the app asked for

void remember(void *track, float left, float right) {
    if (!g_lock.try_lock()) {
        return;  // never block inside AudioTrack code, a missed update is harmless
    }
    g_tracks[track] = std::make_pair(left, right);
    g_lock.unlock();
}

void remember_default(void *track) {
    if (!g_lock.try_lock()) {
        return;
    }
    if (g_tracks.find(track) == g_tracks.end()) {
        g_tracks[track] = std::make_pair(1.0f, 1.0f);
    }
    g_lock.unlock();
}

void forget(void *track) {
    std::lock_guard<std::mutex> guard(g_lock);
    g_tracks.erase(track);
}

int my_setVolume2(void *track, float left, float right) {
    remember(track, left, right);
    if (g_muted.load()) {
        left = 0.0f;
        right = 0.0f;
    }
    return orig_setVolume2(track, left, right);
}

int my_setVolume1(void *track, float volume) {
    remember(track, volume, volume);
    if (g_muted.load()) {
        volume = 0.0f;
    }
    return orig_setVolume1(track, volume);
}

int my_start(void *track) {
    remember_default(track);
    if (g_muted.load() && orig_setVolume2 != nullptr) {
        orig_setVolume2(track, 0.0f, 0.0f);
    }
    return orig_start(track);
}

void my_dtor0(void *track) {
    forget(track);
    orig_dtor0(track);
}

void my_dtor1(void *track) {
    forget(track);
    orig_dtor1(track);
}

void *find_symbol(void *handle, const char *name) {
    void *address = xdl_sym(handle, name, nullptr);
    if (address == nullptr) {
        address = xdl_dsym(handle, name, nullptr);
    }
    return address;
}

bool hook(void *handle, const char *name, void *replacement, void **original) {
    void *target = find_symbol(handle, name);
    if (target == nullptr) {
        LOGD("symbol not found: %s", name);
        return false;
    }
    if (DobbyHook(target, replacement, original) != 0) {
        LOGD("hook failed: %s", name);
        *original = nullptr;
        return false;
    }
    return true;
}

}  // namespace

extern "C" {

// Returns how many of the hooks are in place (0 means native muting is unavailable).
JNIEXPORT jint JNICALL
Java_top_niunaijun_blackbox_fake_service_StayActive_nativeInstall(JNIEnv *, jclass) {
    static std::atomic<int> installed(-1);
    if (installed.load() >= 0) {
        return installed.load();
    }
    int count = 0;
    void *handle = xdl_open("libaudioclient.so", XDL_DEFAULT);
    if (handle == nullptr) {
        handle = xdl_open("libmedia.so", XDL_DEFAULT);  // Android 7 and older
    }
    if (handle != nullptr) {
        if (hook(handle, "_ZN7android10AudioTrack9setVolumeEff", (void *) my_setVolume2,
                 (void **) &orig_setVolume2)) count++;
        if (hook(handle, "_ZN7android10AudioTrack9setVolumeEf", (void *) my_setVolume1,
                 (void **) &orig_setVolume1)) count++;
        if (hook(handle, "_ZN7android10AudioTrack5startEv", (void *) my_start,
                 (void **) &orig_start)) count++;
        if (hook(handle, "_ZN7android10AudioTrackD0Ev", (void *) my_dtor0,
                 (void **) &orig_dtor0)) count++;
        if (hook(handle, "_ZN7android10AudioTrackD1Ev", (void *) my_dtor1,
                 (void **) &orig_dtor1)) count++;
        xdl_close(handle);
    } else {
        LOGD("libaudioclient not found");
    }
    installed.store(count);
    LOGD("native audio hooks installed: %d of 5", count);
    return count;
}

JNIEXPORT void JNICALL
Java_top_niunaijun_blackbox_fake_service_StayActive_nativeSetMuted(JNIEnv *, jclass,
                                                                   jboolean muted) {
    g_muted.store(muted == JNI_TRUE);
    if (orig_setVolume2 == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> guard(g_lock);
    int applied = 0;
    for (auto &entry : g_tracks) {
        if (muted == JNI_TRUE) {
            orig_setVolume2(entry.first, 0.0f, 0.0f);
        } else {
            orig_setVolume2(entry.first, entry.second.first, entry.second.second);
        }
        applied++;
    }
    LOGD("%s %d native track(s)", muted == JNI_TRUE ? "muted" : "unmuted", applied);
}

}  // extern "C"
