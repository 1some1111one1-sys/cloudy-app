package top.niunaijun.blackboxa.runner;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackboxa.view.main.WelcomeActivity;

/**
 * Simple launcher screen: pick an APK, install it into the virtual space, launch it, and
 * control the background keep-alive service.
 */
public class RunnerActivity extends Activity {

    private static final int REQ_PICK_APK = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int USER_ID = 0;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private LinearLayout appList;
    private Button keepButton;
    private Button floatButton;
    private Button silentButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The activity is in the foreground here, so it is allowed to (re)start the service.
        if (KeepAliveService.isEnabled(this)) {
            KeepAliveService.start(this);
        }
        updateKeepButton();
        updateSilentButton();
        syncFloatingButton();
        refreshApps();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("BG App Runner");
        title.setTextSize(24);
        root.addView(title);

        status = new TextView(this);
        status.setText("Pick an APK to run it inside this app.");
        status.setPadding(0, dp(8), 0, dp(12));
        root.addView(status);

        root.addView(button("Pick APK and install", v -> pickApk()));

        keepButton = button("", v -> toggleKeepAlive());
        root.addView(keepButton);

        floatButton = button("", v -> toggleFloatingButton());
        root.addView(floatButton);

        silentButton = button("", v -> toggleSilentAudio());
        root.addView(silentButton);

        root.addView(button("Allow unrestricted battery use", v -> requestBatteryExemption()));
        root.addView(button("Xiaomi: autostart settings", v -> openXiaomiAutostart()));
        root.addView(button("Xiaomi: battery saver (set No restrictions)", v -> openXiaomiBattery()));
        root.addView(button("App info (autostart / background settings)", v -> openAppInfo()));
        root.addView(button("Open full BlackBox manager",
                v -> startActivity(new Intent(this, WelcomeActivity.class))));
        root.addView(button("Copy debug log (for problems)", v -> copyDebugLog()));

        TextView header = new TextView(this);
        header.setText("Apps inside the container");
        header.setTextSize(18);
        header.setPadding(0, dp(16), 0, dp(8));
        root.addView(header);

        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        root.addView(appList);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String text) {
        ui.post(() -> status.setText(text));
    }

    private void updateKeepButton() {
        keepButton.setText(KeepAliveService.isEnabled(this)
                ? "Background keep-alive: ON (tap to turn off)"
                : "Background keep-alive: OFF (tap to turn on)");
    }

    // ---------------------------------------------------------------- Actions

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/octet-stream"});
        startActivityForResult(intent, REQ_PICK_APK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_APK && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            installFromUri(data.getData());
        }
    }

    private void installFromUri(Uri uri) {
        setStatus("Installing...");
        io.execute(() -> {
            File temp = new File(getCacheDir(), "import-" + System.currentTimeMillis() + ".apk");
            try {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(temp)) {
                    if (in == null) {
                        throw new IllegalStateException("Cannot open the selected file");
                    }
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                String problem = notAnApk(temp);
                if (problem != null) {
                    setStatus("Cannot install: " + problem);
                    return;
                }
                String warning = inspectApk(temp);
                InstallResult result = BlackBoxCore.get().installPackageAsUser(temp, USER_ID);
                if (result != null && result.success) {
                    setStatus("Installed: " + result.packageName
                            + (warning == null ? "" : "\n\n" + warning));
                } else {
                    setStatus("Install failed: " + (result == null ? "unknown error" : result.msg));
                }
            } catch (Throwable t) {
                setStatus("Install failed: " + t.getMessage());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
            ui.post(this::refreshApps);
        });
    }

    /** Returns a reason if the file is not a single installable APK, otherwise null. */
    private String notAnApk(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("AndroidManifest.xml") == null) {
                return "this is not a single APK. Files like .xapk, .apks and .apkm are bundles of "
                        + "several APKs, which the container cannot install. Use one full APK instead.";
            }
            return null;
        } catch (Throwable t) {
            return "the file is not a valid APK (" + t.getMessage() + ").";
        }
    }

    /** Returns a warning if the APK looks like one piece of a split install, otherwise null. */
    private String inspectApk(File file) {
        String splitWarning = "Warning: this looks like only the base part of a Play Store split "
                + "install. Its code, libraries or resources may live in separate split files that "
                + "the container cannot load, which usually ends in a blank screen. Use a single "
                + "full (universal) APK of the app instead.";
        try {
            PackageInfo info = getPackageManager()
                    .getPackageArchiveInfo(file.getPath(), PackageManager.GET_META_DATA);
            if (info == null) {
                return "Warning: Android could not read this APK's details. It may be incomplete "
                        + "or part of a split install, in which case the app will probably show a "
                        + "blank screen.";
            }
            android.os.Bundle meta = info.applicationInfo != null ? info.applicationInfo.metaData : null;
            if (meta != null && (meta.getBoolean("com.android.vending.splits.required", false)
                    || meta.containsKey("com.android.vending.splits"))) {
                return splitWarning;
            }
        } catch (Throwable ignored) {
            // no warning if the check itself fails
        }
        return null;
    }

    /** Copies recent warnings and errors from this app (including virtual apps) to the clipboard. */
    private void copyDebugLog() {
        setStatus("Collecting log...");
        io.execute(() -> {
            try {
                Process process = Runtime.getRuntime().exec(
                        new String[]{"logcat", "-d", "-v", "threadtime", "-t", "8000"});
                Pattern level = Pattern.compile("^\\S+\\s+\\S+\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s");
                StringBuilder out = new StringBuilder();
                int lines = 0;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Matcher m = level.matcher(line);
                        boolean keep = line.contains("BlackBox") || line.contains("AndroidRuntime")
                                || line.contains("FATAL") || line.contains("SplashScreen")
                                || (m.find() && "WEF".contains(m.group(1)));
                        if (!keep) {
                            continue;
                        }
                        out.append(line).append('\n');
                        lines++;
                        if (out.length() > 90000) {
                            out.delete(0, out.length() - 70000);
                        }
                    }
                }
                final String text = "BG App Runner log, Android " + Build.VERSION.RELEASE
                        + ", " + Build.MANUFACTURER + " " + Build.MODEL
                        + ", 64-bit host: " + BlackBoxCore.is64Bit() + "\n"
                        + diagnostics() + "\n" + out;
                final int count = lines;
                ui.post(() -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("BG App Runner log", text));
                    }
                    status.setText(count == 0
                            ? "The log is empty. Launch the app first, then try again."
                            : "Copied " + count + " log lines. Paste them into your chat with Claude.");
                });
            } catch (Throwable t) {
                setStatus("Could not read the log: " + t.getMessage());
            }
        });
    }

    /** A short snapshot of the settings and processes that decide whether apps survive. */
    private String diagnostics() {
        StringBuilder d = new StringBuilder();
        try {
            d.append("keepAlive=").append(KeepAliveService.isEnabled(this));
            d.append(" floating=").append(FloatingButtonService.isEnabled(this))
                    .append("/overlayPermission=").append(FloatingButtonService.canDraw(this));
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                d.append(" batteryExempt=").append(pm.isIgnoringBatteryOptimizations(getPackageName()));
            }
            d.append("\nprocesses:");
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list != null) {
                for (android.app.ActivityManager.RunningAppProcessInfo p : list) {
                    if (p.processName.startsWith(getPackageName())) {
                        d.append("\n  ").append(p.processName).append(" importance=").append(p.importance);
                    }
                }
            }
        } catch (Throwable t) {
            d.append("(diagnostics failed: ").append(t.getMessage()).append(")");
        }
        return d.toString();
    }

    private void refreshApps() {
        io.execute(() -> {
            List<PackageInfo> packages;
            try {
                packages = BlackBoxCore.get().getInstalledPackages(0, USER_ID);
            } catch (Throwable t) {
                setStatus("Could not read installed apps: " + t.getMessage());
                return;
            }
            ui.post(() -> renderApps(packages));
        });
    }

    private void renderApps(List<PackageInfo> packages) {
        appList.removeAllViews();
        if (packages == null || packages.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Nothing installed yet.");
            appList.addView(empty);
            return;
        }
        PackageManager pm = getPackageManager();
        for (PackageInfo info : packages) {
            final String pkg = info.packageName;
            String label = pkg;
            try {
                if (info.applicationInfo != null) {
                    label = String.valueOf(info.applicationInfo.loadLabel(pm));
                }
            } catch (Throwable ignored) {
                // fall back to the package name
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            TextView name = new TextView(this);
            name.setText(label + "\n" + pkg);
            row.addView(name);

            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.START);
            buttons.addView(button("Launch", v -> launch(pkg)));
            buttons.addView(button("Stop", v -> stopApp(pkg)));
            buttons.addView(button("Remove", v -> remove(pkg)));
            row.addView(buttons);

            appList.addView(row);
        }
    }

    private void launch(String pkg) {
        try {
            boolean ok = BlackBoxCore.get().launchApk(pkg, USER_ID);
            if (!ok) {
                toast("Could not launch " + pkg);
            }
        } catch (Throwable t) {
            toast("Launch failed: " + t.getMessage());
        }
    }

    private void stopApp(String pkg) {
        io.execute(() -> {
            try {
                BlackBoxCore.get().stopPackage(pkg, USER_ID);
                setStatus("Stopped " + pkg);
            } catch (Throwable t) {
                setStatus("Stop failed: " + t.getMessage());
            }
        });
    }

    private void remove(String pkg) {
        io.execute(() -> {
            try {
                BlackBoxCore.get().uninstallPackageAsUser(pkg, USER_ID);
                setStatus("Removed " + pkg);
            } catch (Throwable t) {
                setStatus("Remove failed: " + t.getMessage());
            }
            ui.post(this::refreshApps);
        });
    }

    private void toggleKeepAlive() {
        if (KeepAliveService.isEnabled(this)) {
            KeepAliveService.stop(this);
            setStatus("Keep-alive turned off.");
        } else {
            KeepAliveService.start(this);
            setStatus("Keep-alive turned on. Also allow unrestricted battery use below.");
        }
        updateKeepButton();
    }

    private void updateSilentButton() {
        silentButton.setText(KeepAliveService.isSilentAudioEnabled(this)
                ? "Silent audio trick: ON (helps on Xiaomi)"
                : "Silent audio trick: OFF (tap to turn on)");
    }

    private void toggleSilentAudio() {
        boolean enable = !KeepAliveService.isSilentAudioEnabled(this);
        KeepAliveService.setSilentAudioEnabled(this, enable);
        if (KeepAliveService.isEnabled(this)) {
            KeepAliveService.start(this); // apply right away
        }
        setStatus(enable
                ? "Silent audio on. The phone sees this app as playing audio while keep-alive is on."
                : "Silent audio off.");
        updateSilentButton();
    }

    private void openXiaomiAutostart() {
        openFirstThatWorks(new Intent().setComponent(new android.content.ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity")));
    }

    private void openXiaomiBattery() {
        openFirstThatWorks(new Intent().setComponent(new android.content.ComponentName(
                        "com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", getPackageName())
                .putExtra("package_label", "BG App Runner"));
    }

    /** Opens the given settings screen, or this app's info page if the phone does not have it. */
    private void openFirstThatWorks(Intent intent) {
        try {
            startActivity(intent);
        } catch (Throwable t) {
            toast("That screen is not available on this phone. Opening App info instead.");
            openAppInfo();
        }
    }

    private void syncFloatingButton() {
        if (FloatingButtonService.isEnabled(this) && FloatingButtonService.canDraw(this)) {
            FloatingButtonService.start(this);
        } else {
            FloatingButtonService.stop(this);
        }
        updateFloatButton();
    }

    private void updateFloatButton() {
        if (!FloatingButtonService.isEnabled(this)) {
            floatButton.setText("Floating button: OFF (tap to turn on)");
        } else if (!FloatingButtonService.canDraw(this)) {
            floatButton.setText("Floating button: needs permission (tap to allow)");
        } else {
            floatButton.setText("Floating button: ON (tap to turn off)");
        }
    }

    private void toggleFloatingButton() {
        boolean on = FloatingButtonService.isEnabled(this);
        if (on && FloatingButtonService.canDraw(this)) {
            FloatingButtonService.setEnabled(this, false);
            setStatus("Floating button turned off.");
        } else {
            FloatingButtonService.setEnabled(this, true);
            if (!FloatingButtonService.canDraw(this)) {
                setStatus("Allow \"Display over other apps\" for BG App Runner, then come back.");
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    openAppInfo();
                }
            } else {
                setStatus("Floating button on. It appears over a virtual app while it is open.");
            }
        }
        syncFloatingButton();
    }

    private void requestBatteryExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("Battery optimization is already off for this app.");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("Open Settings > Battery and allow unrestricted use for this app.");
            }
        }
    }

    private void openAppInfo() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void toast(String text) {
        ui.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }
}
