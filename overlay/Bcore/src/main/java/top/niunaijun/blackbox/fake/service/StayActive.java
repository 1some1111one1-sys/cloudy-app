package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import black.android.app.BRActivityClient;
import black.android.app.servertransaction.BRClientTransaction;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Reflector;

/**
 * Background behaviour for virtual apps, driven by a small flag file that BG App Runner writes
 * when the user taps the floating BG button.
 *
 * The file's text holds the letters of the features that are switched on:
 *  - H (hold): the "pause" and "stop" messages Android sends to the app's screen are dropped inside
 *    the app's own process, so the app keeps believing it is on screen.
 *  - M (mute): the app's own audio players are set to volume 0 while it is in the background and
 *    restored when it comes back. This covers players that register with the system audio service
 *    (MediaPlayer, Java AudioTrack, SoundPool, ExoPlayer ...). Audio produced natively by a game
 *    engine (OpenSL ES / AAudio) is not reachable this way.
 *
 * Everything here is fail-safe: on any doubt or error the message is delivered as usual.
 */
public final class StayActive {
    private static final String TAG = "StayActive";
    private static final String FLAG_NAME = "stay_active.flag";

    private static final List<WeakReference<Object>> PLAYERS = new ArrayList<>();
    private static volatile boolean muted;

    private static boolean nativeReady;
    private static boolean nativeSkipLogged;

    private static boolean muteSwitchOn() {
        try {
            Context context = BlackBoxCore.getContext();
            return context == null || context.getSharedPreferences("bg_runner_prefs",
                    Context.MODE_PRIVATE).getBoolean("mute_guest_audio", true);
        } catch (Throwable t) {
            return true;
        }
    }

    private static native int nativeInstall();

    private static native void nativeSetMuted(boolean muted);

    private StayActive() {
    }

    /** Hooks the native audio layer once per process. Safe to call repeatedly. */
    private static synchronized void ensureNative() {
        if (nativeReady) {
            return;
        }
        // The hooks change how the app's own audio code runs, so they are only put in when the
        // "Mute app sound in background" switch is on. A game that misbehaves can be tried with it off.
        if (!muteSwitchOn()) {
            if (!nativeSkipLogged) {
                nativeSkipLogged = true;
                Log.d(TAG, "native audio hooks skipped: mute switch is off");
            }
            return;
        }
        try {
            System.loadLibrary("bgmute");
            int hooks = nativeInstall();
            nativeReady = hooks > 0;
            Log.d(TAG, "native audio hooks: " + hooks + " of 5");
        } catch (Throwable t) {
            Log.w(TAG, "native audio mute unavailable", t);
        }
    }

    /** Called once when a virtual app process starts, so its native tracks are tracked from the start. */
    public static void prepare() {
        try {
            if (BActivityThread.getAppConfig() != null) {
                ensureNative();
            }
        } catch (Throwable ignored) {
            // optional feature
        }
    }

    private static File flagFile() {
        try {
            Context context = BlackBoxCore.getContext();
            return context == null ? null : new File(context.getFilesDir(), FLAG_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readFlag(File flag) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(flag)) {
            byte[] buffer = new byte[16];
            int n = in.read(buffer);
            return n <= 0 ? "HM" : new String(buffer, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "HM";
        }
    }

    /** Returns true when this transaction must be swallowed instead of executed. */
    public static boolean holdIfNeeded(Object transaction) {
        try {
            if (transaction == null || BActivityThread.getAppConfig() == null) {
                return false; // not a virtual-app process
            }
            ensureNative();
            Object request = BRClientTransaction.get(transaction).mLifecycleStateRequest();
            if (request == null) {
                return false;
            }
            String kind = request.getClass().getSimpleName();
            File flag = flagFile();
            if ("ResumeActivityItem".equals(kind)) {
                if (muted) {
                    setMuted(false); // the user is back
                }
                if (flag != null && flag.exists()) {
                    flag.delete();
                }
                return false;
            }
            boolean pause = "PauseActivityItem".equals(kind);
            boolean stop = "StopActivityItem".equals(kind);
            if (!pause && !stop) {
                return false;
            }
            if (flag == null || !flag.exists()) {
                return false;
            }
            List<Object> callbacks = BRClientTransaction.get(transaction).mActivityCallbacks();
            if (callbacks != null && !callbacks.isEmpty()) {
                return false; // carries something else (a launch, a result, ...)
            }
            if (pause) {
                Object finished = Reflector.QuietReflector.on(request.getClass())
                        .field("mFinished").get(request);
                if (Boolean.TRUE.equals(finished)) {
                    return false; // the screen is closing for real
                }
            }
            String mode = readFlag(flag);
            if (mode.indexOf('M') >= 0) {
                setMuted(true);
            }
            if (mode.indexOf('H') < 0) {
                return false;
            }
            if (pause) {
                IBinder token = BRClientTransaction.get(transaction).mActivityToken();
                acknowledgePause(token);
                Log.d(TAG, "held pause");
            } else {
                Log.d(TAG, "held stop");
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "falling back to normal behaviour", t);
        }
        return false;
    }

    /** Called after the system audio service registered a new player for this app. */
    public static void onTrackPlayer(Object[] args) {
        try {
            if (BActivityThread.getAppConfig() == null || args == null || args.length == 0
                    || args[0] == null) {
                return; // not a virtual-app process
            }
            Object player = Reflector.QuietReflector.on(args[0].getClass())
                    .field("mIPlayer").get(args[0]);
            if (player == null) {
                return;
            }
            synchronized (PLAYERS) {
                for (int i = PLAYERS.size() - 1; i >= 0; i--) {
                    if (PLAYERS.get(i).get() == null) {
                        PLAYERS.remove(i);
                    }
                }
                PLAYERS.add(new WeakReference<>(player));
            }
            if (muted) {
                setPlayerVolume(player, 0f);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not track player", t);
        }
    }

    private static void setMuted(boolean mute) {
        muted = mute;
        ensureNative();
        if (nativeReady) {
            try {
                nativeSetMuted(mute);
            } catch (Throwable ignored) {
                // optional feature
            }
        }
        List<Object> players = new ArrayList<>();
        synchronized (PLAYERS) {
            for (WeakReference<Object> ref : PLAYERS) {
                Object player = ref.get();
                if (player != null) {
                    players.add(player);
                }
            }
        }
        for (Object player : players) {
            setPlayerVolume(player, mute ? 0f : 1f);
        }
        Log.d(TAG, (mute ? "muted " : "unmuted ") + players.size() + " player(s)");
    }

    /** IPlayer.setVolume sets the system's own volume multiplier, separate from the app's volume. */
    private static void setPlayerVolume(Object player, float volume) {
        try {
            Reflector.QuietReflector.on("android.media.IPlayer")
                    .method("setVolume", float.class)
                    .callByCaller(player, volume);
        } catch (Throwable ignored) {
            // the player is gone
        }
    }

    /** Tell the system the pause is done so it does not wait for its timeout. */
    private static void acknowledgePause(IBinder token) {
        try {
            Object client = BRActivityClient.get().getInstance();
            if (client != null && token != null) {
                Reflector.QuietReflector.on(client.getClass())
                        .method("activityPaused", IBinder.class)
                        .callByCaller(client, token);
            }
        } catch (Throwable ignored) {
            // the system times out after about half a second; harmless
        }
    }
}
