package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.util.List;

import black.android.app.BRActivityClient;
import black.android.app.servertransaction.BRClientTransaction;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Reflector;

/**
 * Lets a virtual app keep believing it is on screen after the user leaves it.
 *
 * While the flag file exists (BG App Runner creates it when the user taps the floating BG button),
 * the "pause" and "stop" messages that Android sends to a virtual app's screen are dropped inside
 * the app's own process. The app never runs onPause/onStop, so games and players keep going and
 * the app does not tear its state down. A "resume" message, or the runner coming back to the
 * front, clears the flag and everything returns to normal.
 *
 * Everything here is fail-safe: on any doubt or error the message is delivered as usual.
 */
public final class StayActive {
    private static final String TAG = "StayActive";
    private static final String FLAG_NAME = "stay_active.flag";

    private StayActive() {
    }

    private static File flagFile() {
        try {
            Context context = BlackBoxCore.getContext();
            return context == null ? null : new File(context.getFilesDir(), FLAG_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Returns true when this transaction must be swallowed instead of executed. */
    public static boolean holdIfNeeded(Object transaction) {
        try {
            if (transaction == null || BActivityThread.getAppConfig() == null) {
                return false; // not a virtual-app process
            }
            File flag = flagFile();
            if (flag == null || !flag.exists()) {
                return false;
            }
            Object request = BRClientTransaction.get(transaction).mLifecycleStateRequest();
            if (request == null) {
                return false;
            }
            String kind = request.getClass().getSimpleName();
            if ("ResumeActivityItem".equals(kind)) {
                flag.delete(); // the user is back
                return false;
            }
            List<Object> callbacks = BRClientTransaction.get(transaction).mActivityCallbacks();
            if (callbacks != null && !callbacks.isEmpty()) {
                return false; // carries something else (a launch, a result, ...)
            }
            IBinder token = BRClientTransaction.get(transaction).mActivityToken();
            if ("PauseActivityItem".equals(kind)) {
                Object finished = Reflector.QuietReflector.on(request.getClass())
                        .field("mFinished").get(request);
                if (Boolean.TRUE.equals(finished)) {
                    return false; // the screen is closing for real
                }
                acknowledgePause(token);
                Log.d(TAG, "held pause");
                return true;
            }
            if ("StopActivityItem".equals(kind)) {
                Log.d(TAG, "held stop");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "falling back to normal behaviour", t);
        }
        return false;
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
