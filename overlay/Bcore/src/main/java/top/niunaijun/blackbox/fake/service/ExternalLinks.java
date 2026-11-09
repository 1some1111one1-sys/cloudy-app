package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import top.niunaijun.blackbox.app.BActivityThread;

/**
 * Web links opened by a virtual app should open in the phone's real browser, not in an activity of
 * a virtual app that happens to declare a matching https filter.
 */
public final class ExternalLinks {
    private static Set<String> sBrowsers;
    private static long sBrowsersAt;

    private ExternalLinks() {
    }

    /**
     * Called just before a virtual app sends the user to the phone's browser. The app has to
     * still be alive when the user comes back (a sign-in is finished by the same running app),
     * so for the trip it keeps the app's process protected: a short-lived keep-alive service
     * runs, and the app is not told its screen went away.
     */
    public static void protectForTrip() {
        try {
            Context context = top.niunaijun.blackbox.BlackBoxCore.getContext();
            if (context == null) {
                return;
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new java.io.File(context.getFilesDir(), "stay_active.flag"), false)) {
                out.write("H".getBytes("UTF-8"));
            }
            Intent service = new Intent().setComponent(new ComponentName(context.getPackageName(),
                    "top.niunaijun.blackboxa.runner.KeepAliveService"));
            service.putExtra("temporary_trip", true);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Throwable ignored) {
            // the trip still works, the app is just less protected
        }
    }

    /**
     * True for the phone's web browsers. Apps in the container normally cannot see apps outside
     * it, so sign-in code that looks for a browser (to open the page there) found none and fell
     * back to showing the page inside the app, where Microsoft's sign-in does not work.
     */
    public static boolean isBrowserPackage(String packageName) {
        if (packageName == null) {
            return false;
        }
        try {
            return browsers().contains(packageName);
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized Set<String> browsers() {
        long now = SystemClock.elapsedRealtime();
        if (sBrowsers == null || now - sBrowsersAt > 60_000) {
            Set<String> found = new HashSet<>();
            Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"));
            probe.addCategory(Intent.CATEGORY_BROWSABLE);
            PackageManager pm = top.niunaijun.blackbox.BlackBoxCore.getContext().getPackageManager();
            List<ResolveInfo> list = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL);
            if (list != null) {
                for (ResolveInfo info : list) {
                    if (info.activityInfo != null) {
                        found.add(info.activityInfo.packageName);
                    }
                }
            }
            sBrowsers = found;
            sBrowsersAt = now;
        }
        return sBrowsers;
    }

    /** True for a plain "open this http(s) address" request that names no specific app or screen. */
    public static boolean isWebLink(Intent intent) {
        try {
            if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())
                    || intent.getComponent() != null || intent.getSelector() != null) {
                return false;
            }
            String pkg = intent.getPackage();
            if (pkg != null && pkg.equals(BActivityThread.getAppPackageName())) {
                return false; // the app asked for itself
            }
            Uri data = intent.getData();
            String scheme = data == null ? null : data.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Throwable t) {
            return false;
        }
    }
}
