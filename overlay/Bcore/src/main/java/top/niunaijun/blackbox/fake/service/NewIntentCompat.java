package top.niunaijun.blackbox.fake.service;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import black.android.app.BRActivityThread;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Hands a new intent (a file to open, the return of a browser sign-in) to an activity that is
 * already running inside a virtual app.
 *
 * The engine only knows the entry points Android 12 and older have. Since Android 13 the platform
 * takes the activity's record instead of its token, none of the engine's three variants exist, and
 * the intent was dropped without a trace: the app came to the front but never received anything.
 *
 * Android 13 and newer also moved the "pause, deliver, resume" dance for an activity that is on
 * screen out of the call that delivers the intent and into the system's own transaction, which a
 * direct call from here bypasses. A sign-in screen that finishes its job in onResume (after
 * onNewIntent) would then wait forever, so for an activity that believes it is resumed this class
 * does that dance itself.
 */
public final class NewIntentCompat {
    private static final String TAG = "NewIntentCompat";

    private NewIntentCompat() {
    }

    /** Runs on the virtual app's main thread. {@code referrerIntent} is an android.app.ReferrerIntent. */
    public static void deliver(Object activityThread, IBinder token, Intent referrerIntent) {
        Object record = null;
        try {
            Map<IBinder, Object> activities = BRActivityThread.get(activityThread).mActivities();
            record = activities == null ? null : activities.get(token);
        } catch (Throwable t) {
            Slog.w(TAG, "OpenWith: cannot look up the running activity: " + t);
        }
        if (record == null) {
            Slog.w(TAG, "OpenWith: no running activity for this token, so the new intent was not delivered");
            return;
        }

        Activity activity = null;
        try {
            activity = BActivityThread.getActivityByToken(token);
        } catch (Throwable ignored) {
            // the platform path below does not need it
        }

        // An activity that believes it is on screen gets what Android gives it: pause, the new
        // intent, resume.
        if (activity != null && isResumed(activity, record)
                && deliverToResumed(activityThread, activity, referrerIntent)) {
            return;
        }

        List<Intent> intents = Collections.singletonList(referrerIntent);
        for (String name : new String[]{"handleNewIntent", "deliverNewIntents"}) {
            Method method = find(activityThread.getClass(), name, record.getClass());
            if (method == null) {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(activityThread, record, intents);
                Slog.d(TAG, "OpenWith: new intent delivered with " + name + " (" + describe(referrerIntent) + ")");
                return;
            } catch (InvocationTargetException e) {
                Slog.w(TAG, "OpenWith: " + name + " threw " + e.getCause());
            } catch (Throwable t) {
                Slog.w(TAG, "OpenWith: " + name + " could not be called: " + t);
            }
        }

        // Last resort: call the activity's onNewIntent ourselves.
        try {
            Instrumentation instrumentation = BRActivityThread.get(activityThread).mInstrumentation();
            if (activity != null && instrumentation != null) {
                instrumentation.callActivityOnNewIntent(activity, new Intent(referrerIntent));
                Slog.d(TAG, "OpenWith: new intent delivered directly (" + describe(referrerIntent) + ")");
            } else {
                Slog.w(TAG, "OpenWith: the activity was not available, new intent not delivered");
            }
        } catch (Throwable t) {
            Slog.w(TAG, "OpenWith: direct delivery failed: " + t);
        }
    }

    /**
     * Pause, onNewIntent, resume, on the app's main thread. Returns false (and has changed
     * nothing) when the first step could not be done, so the caller can use another way.
     */
    private static boolean deliverToResumed(Object activityThread, Activity activity, Intent referrerIntent) {
        Instrumentation instrumentation;
        try {
            instrumentation = BRActivityThread.get(activityThread).mInstrumentation();
        } catch (Throwable t) {
            return false;
        }
        if (instrumentation == null) {
            return false;
        }
        boolean paused = false;
        boolean delivered = false;
        try {
            setBoolean(activity, "mTemporaryPause", true);
            instrumentation.callActivityOnPause(activity);
            paused = true;
            Intent copy = new Intent(referrerIntent);
            copy.setExtrasClassLoader(activity.getClassLoader());
            instrumentation.callActivityOnNewIntent(activity, copy);
            delivered = true;
        } catch (Throwable t) {
            Slog.w(TAG, "OpenWith: delivering to the resumed activity failed: " + t);
        } finally {
            if (paused) {
                resume(activity, instrumentation);
            }
            setBoolean(activity, "mTemporaryPause", false);
        }
        if (delivered) {
            Slog.d(TAG, "OpenWith: new intent delivered to the resumed " + activity.getClass().getName()
                    + " with pause, onNewIntent, resume (" + describe(referrerIntent) + ")");
        }
        // Once the activity was paused it has to be resumed, so there is no second attempt.
        return paused;
    }

    private static void resume(Activity activity, Instrumentation instrumentation) {
        try {
            Method performResume = Activity.class.getDeclaredMethod("performResume", boolean.class, String.class);
            performResume.setAccessible(true);
            performResume.invoke(activity, false, "newIntent");
            return;
        } catch (Throwable t) {
            Slog.w(TAG, "OpenWith: performResume is not available (" + t + "), using the plain resume");
        }
        try {
            instrumentation.callActivityOnResume(activity);
        } catch (Throwable t) {
            Slog.w(TAG, "OpenWith: resuming the activity failed: " + t);
        }
    }

    /** True when the app's own bookkeeping says the activity is currently resumed. */
    private static boolean isResumed(Activity activity, Object record) {
        Boolean resumed = readBoolean(Activity.class, activity, "mResumed");
        if (resumed != null) {
            return resumed;
        }
        Boolean paused = readBoolean(record.getClass(), record, "paused");
        return paused != null && !paused;
    }

    private static Boolean readBoolean(Class<?> type, Object target, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setBoolean(Activity activity, String name, boolean value) {
        try {
            Field field = Activity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(activity, value);
        } catch (Throwable ignored) {
            // only a detail of the pause/resume dance
        }
    }

    /** The platform method that takes (ActivityClientRecord, List), whatever it is called here. */
    private static Method find(Class<?> type, String name, Class<?> recordClass) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] != IBinder.class && p[0].isAssignableFrom(recordClass)
                        && List.class.isAssignableFrom(p[1])) {
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * A short description for the debug log. It leaves out query strings and anything after the
     * address, because sign-in links carry codes that must not end up in a log someone pastes.
     */
    public static String describe(Intent intent) {
        if (intent == null) {
            return "no intent";
        }
        StringBuilder out = new StringBuilder();
        out.append("action=").append(intent.getAction());
        if (intent.getComponent() != null) {
            out.append(" component=").append(intent.getComponent().flattenToShortString());
        }
        Uri data = intent.getData();
        if (data != null) {
            out.append(" scheme=").append(data.getScheme());
            String scheme = data.getScheme();
            if ("content".equals(scheme) || "file".equals(scheme)) {
                out.append(" authority=").append(data.getAuthority());
                out.append(" file=").append(data.getLastPathSegment());
            }
        }
        out.append(" type=").append(intent.getType());
        return out.toString();
    }
}
