package top.niunaijun.blackboxa.runner;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
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
 * Main screen: add an APK, launch the apps installed in the container, and control how they are
 * kept alive in the background.
 */
public class RunnerActivity extends Activity {

    private static final int REQ_PICK_APK = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int USER_ID = 0;

    private static final int COLOR_BACKGROUND = 0xFFF4F6F8;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_BORDER = 0xFFE5E7EB;
    private static final int COLOR_TEXT = 0xFF111827;
    private static final int COLOR_MUTED = 0xFF6B7280;
    private static final int COLOR_ACCENT = 0xFF1E88E5;
    private static final int COLOR_DANGER = 0xFFD32F2F;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private LinearLayout appList;
    private Switch keepSwitch;
    private Switch floatSwitch;
    private Switch silentSwitch;
    private TextView floatDescription;
    private TextView silentDescription;
    private static final String SILENT_DESCRIPTION = "Only turn this on if apps still stop with the options above. "
            + "Plays inaudible audio and shows a media player, so the phone treats this app like a music app. "
            + "Uses more battery.";
    private boolean syncing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(COLOR_BACKGROUND);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The activity is in the foreground here, so it is allowed to (re)start the services.
        if (KeepAliveService.isEnabled(this)) {
            KeepAliveService.start(this);
        }
        if (FloatingButtonService.isEnabled(this) && FloatingButtonService.canDraw(this)) {
            FloatingButtonService.start(this);
        } else {
            FloatingButtonService.stop(this);
        }
        syncSwitches();
        refreshApps();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI building

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));

        // Header
        root.addView(text("BG App Runner", 26, COLOR_TEXT, true));
        TextView subtitle = text("Run another app inside this one and keep it alive in the background.",
                14, COLOR_MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        TextView addApk = filledButton("Add APK", v -> pickApk());
        addApk.setTextSize(16);
        addApk.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(addApk, cardParams());

        // Status
        LinearLayout statusCard = card();
        status = text("Pick an APK to run it inside this app.", 14, COLOR_TEXT, false);
        statusCard.addView(status);
        root.addView(statusCard, cardParams());

        // Apps
        LinearLayout appsCard = card();
        appsCard.addView(sectionTitle("Apps in the container"));
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appsCard.addView(appList);
        root.addView(appsCard, cardParams());

        // Background
        LinearLayout bgCard = card();
        bgCard.addView(sectionTitle("Keep running in the background"));

        keepSwitch = addSwitchRow(bgCard, "Keep apps running",
                "Foreground service and wake lock. Turn this on first.",
                (button, checked) -> {
                    if (syncing) {
                        return;
                    }
                    if (checked) {
                        KeepAliveService.start(this);
                        setStatus("Keep-alive is on. Allow unrestricted battery use below as well.");
                    } else {
                        KeepAliveService.stop(this);
                        setStatus("Keep-alive turned off.");
                    }
                });

        bgCard.addView(divider());
        floatSwitch = addSwitchRow(bgCard, "Floating BG button",
                "A button over a running app. Tap it to keep the app running and go home.",
                (button, checked) -> {
                    if (syncing) {
                        return;
                    }
                    onFloatingToggled(checked);
                });
        floatDescription = lastDescription;

        bgCard.addView(divider());
        silentSwitch = addSwitchRow(bgCard, "Backup: silent audio mode",
                SILENT_DESCRIPTION,
                (button, checked) -> {
                    if (syncing) {
                        return;
                    }
                    KeepAliveService.setSilentAudioEnabled(this, checked);
                    if (KeepAliveService.isEnabled(this)) {
                        KeepAliveService.start(this); // apply right away
                        setStatus(checked ? "Starting silent audio mode..." : "Silent audio mode is off.");
                        ui.postDelayed(this::syncSwitches, 900);
                    } else {
                        setStatus("Silent audio mode will start when \"Keep apps running\" is on.");
                    }
                });
        silentDescription = lastDescription;

        bgCard.addView(divider());
        bgCard.addView(actionRow("Allow unrestricted battery use", v -> requestBatteryExemption()));
        bgCard.addView(actionRow("Xiaomi: autostart settings", v -> openXiaomiAutostart()));
        bgCard.addView(actionRow("Xiaomi: battery saver (choose No restrictions)", v -> openXiaomiBattery()));
        bgCard.addView(actionRow("Notification settings", v -> openNotificationSettings()));
        bgCard.addView(actionRow("App info", v -> openAppInfo()));
        root.addView(bgCard, cardParams());

        // Tools
        LinearLayout toolsCard = card();
        toolsCard.addView(sectionTitle("Tools"));
        toolsCard.addView(actionRow("Open full BlackBox manager",
                v -> startActivity(new Intent(this, WelcomeActivity.class))));
        toolsCard.addView(actionRow("Copy debug log (for problems)", v -> copyDebugLog()));
        root.addView(toolsCard, cardParams());

        TextView tip = text("Tip: leave a running app with the BG button, then press Home. "
                + "Do not swipe this app away in recents, and lock it there on Xiaomi.",
                12, COLOR_MUTED, false);
        tip.setPadding(dp(4), dp(4), dp(4), 0);
        root.addView(tip);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        scroll.setFillViewport(true);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private TextView lastDescription;

    private Switch addSwitchRow(LinearLayout parent, String title, String description,
                                CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, COLOR_TEXT, true));
        TextView desc = text(description, 13, COLOR_MUTED, false);
        desc.setPadding(0, dp(2), 0, 0);
        labels.addView(desc);
        lastDescription = desc;
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setOnCheckedChangeListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        row.addView(toggle, lp);

        parent.addView(row);
        return toggle;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(COLOR_CARD, 16, COLOR_BORDER, 1));
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        return lp;
    }

    private TextView sectionTitle(String label) {
        TextView t = text(label, 13, COLOR_ACCENT, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(COLOR_BORDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private TextView actionRow(String label, View.OnClickListener listener) {
        TextView row = text(label, 15, COLOR_ACCENT, false);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setClickable(true);
        row.setOnClickListener(listener);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId);
        }
        return row;
    }

    private TextView filledButton(String label, View.OnClickListener listener) {
        TextView b = text(label, 14, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(rounded(COLOR_ACCENT, 12, COLOR_ACCENT, 0));
        b.setClickable(true);
        b.setOnClickListener(listener);
        return b;
    }

    private TextView outlineButton(String label, int color, View.OnClickListener listener) {
        TextView b = text(label, 14, color, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(rounded(COLOR_CARD, 12, color, 1));
        b.setClickable(true);
        b.setOnClickListener(listener);
        return b;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            d.setStroke(dp(strokeDp), strokeColor);
        }
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String text) {
        ui.post(() -> status.setText(text));
    }

    private void toast(String text) {
        ui.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    // ---------------------------------------------------------------- Switch state

    private void syncSwitches() {
        syncing = true;
        keepSwitch.setChecked(KeepAliveService.isEnabled(this));
        boolean floatWanted = FloatingButtonService.isEnabled(this);
        boolean canDraw = FloatingButtonService.canDraw(this);
        floatSwitch.setChecked(floatWanted && canDraw);
        floatDescription.setText(floatWanted && !canDraw
                ? "Needs \"Display over other apps\". Turn this on and allow it."
                : "A button over a running app. Tap it to keep the app running and go home.");
        boolean silentOn = KeepAliveService.isSilentAudioEnabled(this);
        silentSwitch.setChecked(silentOn);
        silentDescription.setText(silentOn
                ? SILENT_DESCRIPTION + "\nStatus: " + KeepAliveService.silentAudioState(this)
                : SILENT_DESCRIPTION);
        syncing = false;
        warnIfNotificationsOff();
    }

    private boolean notificationsEnabled() {
        if (Build.VERSION.SDK_INT < 24) {
            return true;
        }
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return nm == null || nm.areNotificationsEnabled();
    }

    private void warnIfNotificationsOff() {
        if (!notificationsEnabled()) {
            status.setText("Notifications are blocked for this app, so the keep-alive notification and "
                    + "the media player cannot show. Tap \"Notification settings\" and allow them.");
        }
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Throwable t) {
            openAppInfo();
        }
    }

    private void onFloatingToggled(boolean checked) {
        if (!checked) {
            FloatingButtonService.setEnabled(this, false);
            FloatingButtonService.stop(this);
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
                FloatingButtonService.start(this);
                setStatus("Floating button on. It appears over a virtual app while it is open.");
            }
        }
        syncSwitches();
    }

    // ---------------------------------------------------------------- Install

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
            Bundle meta = info.applicationInfo != null ? info.applicationInfo.metaData : null;
            if (meta != null && (meta.getBoolean("com.android.vending.splits.required", false)
                    || meta.containsKey("com.android.vending.splits"))) {
                return splitWarning;
            }
        } catch (Throwable ignored) {
            // no warning if the check itself fails
        }
        return null;
    }

    // ---------------------------------------------------------------- App list

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
            TextView empty = text("Nothing here yet. Tap Add APK to install your first app.",
                    14, COLOR_MUTED, false);
            empty.setPadding(0, dp(6), 0, dp(6));
            appList.addView(empty);
            return;
        }
        PackageManager pm = getPackageManager();
        boolean first = true;
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
            if (!first) {
                appList.addView(divider());
            }
            first = false;
            appList.addView(appRow(label, pkg));
        }
    }

    private View appRow(String label, String pkg) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        String initial = label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase();
        TextView badge = text(initial, 18, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        int[] palette = {0xFF1E88E5, 0xFF8E24AA, 0xFF43A047, 0xFFF4511E, 0xFF00897B, 0xFF3949AB};
        badge.setBackground(rounded(palette[Math.abs(pkg.hashCode()) % palette.length], 20, 0, 0));
        top.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(12), 0, 0, 0);
        TextView name = text(label, 16, COLOR_TEXT, true);
        name.setSingleLine(true);
        names.addView(name);
        TextView pkgView = text(pkg, 12, COLOR_MUTED, false);
        pkgView.setSingleLine(true);
        names.addView(pkgView);
        top.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(top);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(10), 0, 0);
        buttons.addView(filledButton("Launch", v -> launch(pkg)));
        buttons.addView(spacer());
        buttons.addView(outlineButton("Stop", COLOR_ACCENT, v -> stopApp(pkg)));
        buttons.addView(spacer());
        buttons.addView(outlineButton("Remove", COLOR_DANGER, v -> remove(pkg)));
        row.addView(buttons);
        return row;
    }

    private View spacer() {
        return new View(this) {
            {
                setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
            }
        };
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

    // ---------------------------------------------------------------- Settings shortcuts

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

    private void openXiaomiAutostart() {
        openFirstThatWorks(new Intent().setComponent(new ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity")));
    }

    private void openXiaomiBattery() {
        openFirstThatWorks(new Intent().setComponent(new ComponentName(
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

    // ---------------------------------------------------------------- Debug log

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
            d.append("androidUser=").append(android.os.Process.myUid() / 100000).append(" ");
            d.append("keepAlive=").append(KeepAliveService.isEnabled(this));
            d.append(" silentAudio=").append(KeepAliveService.isSilentAudioEnabled(this))
                    .append(" (").append(KeepAliveService.silentAudioState(this)).append(")");
            d.append(" notificationsEnabled=").append(notificationsEnabled());
            d.append(" floating=").append(FloatingButtonService.isEnabled(this))
                    .append("/overlayPermission=").append(FloatingButtonService.canDraw(this));
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                d.append(" batteryExempt=").append(pm.isIgnoringBatteryOptimizations(getPackageName()));
            }
            d.append("\nprocesses:");
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list != null) {
                for (ActivityManager.RunningAppProcessInfo p : list) {
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
}
