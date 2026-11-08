package top.niunaijun.blackbox.fake.service;

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
