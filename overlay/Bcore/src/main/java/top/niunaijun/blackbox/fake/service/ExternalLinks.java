package top.niunaijun.blackbox.fake.service;

import android.content.Intent;
import android.net.Uri;

import top.niunaijun.blackbox.app.BActivityThread;

/**
 * Web links opened by a virtual app should open in the phone's real browser, not in an activity of
 * a virtual app that happens to declare a matching https filter.
 */
public final class ExternalLinks {
    private ExternalLinks() {
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
