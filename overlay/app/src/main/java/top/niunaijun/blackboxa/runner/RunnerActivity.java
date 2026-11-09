package top.niunaijun.blackboxa.runner;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
import java.util.ArrayList;
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
 * Main screen. From the top: the BlackBox manager (the main way to add and manage apps), the apps
 * that are installed in the container, then the settings that decide how they behave, grouped by
 * purpose.
 */
public class RunnerActivity extends Activity {

    private static final int REQ_PICK_APK = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int USER_ID = 0;
    private static final String GMS_DESCRIPTION = "Copies this phone's Google Play services, Play Store "
            + "and Play Games into the container so apps can sign in and use Google features. "
            + "Needs them installed on the phone.";
    private static final String SILENT_DESCRIPTION = "Only turn this on if apps still stop with the options above. "
            + "Plays inaudible audio and shows a media player, so the phone treats this app like a music app. "
            + "Uses more battery.";
    private static final String FLOAT_DESCRIPTION = "A button over a running app. Tap it to keep the app "
            + "running and go home.";

    private static final int COLOR_BACKGROUND = 0xFFF3F5F9;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_BORDER = 0xFFE3E7EE;
    private static final int COLOR_TEXT = 0xFF111827;
    private static final int COLOR_MUTED = 0xFF5B6575;
    private static final int COLOR_ACCENT = 0xFF1565C0;
    private static final int COLOR_ACCENT_SOFT = 0xFFE7F0FB;
    private static final int COLOR_SUCCESS = 0xFF2E7D32;
    private static final int COLOR_SUCCESS_SOFT = 0xFFE6F4E8;
    private static final int COLOR_WARN = 0xFF9A5B00;
    private static final int COLOR_WARN_SOFT = 0xFFFDF0DA;
    private static final int COLOR_DANGER = 0xFFC62828;
    private static final int COLOR_NEUTRAL_SOFT = 0xFFEAEDF2;
    private static final int COLOR_HERO_START = 0xFF0F172A;
    private static final int COLOR_HERO_END = 0xFF1E3A8A;
    private static final int COLOR_SNACKBAR = 0xFF1F2937;

    private static final int STATUS_VISIBLE_MS = 8000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Message bar at the bottom of the screen (declared first: the runnable below uses it).
    private LinearLayout statusBar;
    private TextView status;
    private final Runnable hideStatus = new Runnable() {
        @Override
        public void run() {
            if (statusBar != null) {
                statusBar.setVisibility(View.GONE);
            }
        }
    };

    private TextView keepPill;
    private TextView appsTitle;
    private LinearLayout appList;
    private Switch keepSwitch;
    private Switch floatSwitch;
    private Switch silentSwitch;
    private Switch stayActiveSwitch;
    private Switch muteSwitch;
    private Switch gmsSwitch;
    private Switch openWithSwitch;
    private TextView gmsDescription;
    private TextView floatDescription;
    private TextView silentDescription;
    private TextView batteryChip;
    private TextView notificationChip;
    private TextView lastDescription;
    private boolean syncing;
    private boolean gmsBusy;

    /** One app installed in the container, with everything needed to draw its row. */
    private static final class AppItem {
        final String label;
        final String pkg;
        final Drawable icon;

        AppItem(String label, String pkg, Drawable icon) {
            this.label = label;
            this.pkg = pkg;
            this.icon = icon;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(COLOR_BACKGROUND);
            getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
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
        KeepAliveService.setStayActiveFlag(this, false);
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
        ui.removeCallbacks(hideStatus);
        io.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI building

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Extra room at the bottom so the message bar never covers the last row for good.
        root.setPadding(dp(16), dp(20), dp(16), dp(96));

        root.addView(buildHeader());
        root.addView(buildManagerHero(), block(18));
        root.addView(buildAppsSection(), block(0));

        // Background
        LinearLayout keep = section(root, "Keep running in the background");
        keepSwitch = switchRow(keep, "Keep apps running",
                "Runs a service with a notification and keeps the phone awake for your apps. "
                        + "Turn this on first.",
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
                    updatePill(checked);
                });
        floatSwitch = switchRow(keep, "Floating BG button", FLOAT_DESCRIPTION,
                (button, checked) -> {
                    if (syncing) {
                        return;
                    }
                    onFloatingToggled(checked);
                });
        floatDescription = lastDescription;
        stayActiveSwitch = switchRow(keep, "Stay active in background",
                "After tapping BG, the app is told it is still on screen so it does not pause or "
                        + "reload. Experimental: turn it off if an app misbehaves.",
                (button, checked) -> {
                    if (syncing) {
                        return;
                    }
                    KeepAliveService.setStayActiveEnabled(this, checked);
                    setStatus(checked ? "Stay active is on." : "Stay active is off.");
                });

        // Sound
        LinearLayout sound = section(root, "Sound");
        muteSwitch = switchRow(sound, "Mute app sound in background",
                "After tapping BG, the app's own sound is muted so only this app's media player "
                        + "stays. Works for most apps and games; some low-latency "
                        + "game audio may still be heard.",
                (button, checked) -> {
                    if (syncing) {
                        return;
                    }
                    KeepAliveService.setMuteGuestEnabled(this, checked);
                    setStatus(checked ? "The app's sound will be muted in the background."
                            : "The app's sound will keep playing in the background.");
                });
        silentSwitch = switchRow(sound, "Backup: silent audio mode", SILENT_DESCRIPTION,
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

        // Phone settings
        LinearLayout phone = section(root, "Allow on this phone");
        batteryChip = navRow(phone, "Allow unrestricted battery use",
                "Stops the phone from pausing your apps.", true, v -> requestBatteryExemption());
        navRow(phone, "Autostart in background",
                "Lets the app start itself and keep running.", false, v -> openAutostart());
        navRow(phone, "Battery saver (choose No restrictions)",
                "The phone's own per-app battery limit.", false, v -> openBatterySaver());
        notificationChip = navRow(phone, "Notification settings",
                "Needed for the keep-alive notification and the media player.", true,
                v -> openNotificationSettings());
        navRow(phone, "App info", "Permissions, storage and the rest of this app's settings.",
                false, v -> openAppInfo());

        // Files
        LinearLayout files = section(root, "Opening files");
        openWithSwitch = switchRow(files, "Show in the \"Open with\" menu",
                "When you open a file (PDF, document, image...) from another app, \"Open in BG App "
                        + "Runner\" appears in the list. It lets you send the file to any app from "
                        + "the container. The file is copied first, so changes are saved to the copy.",
                (button, checked) -> {
                    if (syncing) {
                        return;
                    }
                    setOpenWithEnabled(checked);
                    setStatus(checked ? "\"Open with\" menu entry is on."
                            : "\"Open with\" menu entry is off.");
                });

        // Google services
        LinearLayout google = section(root, "Google services");
        gmsSwitch = switchRow(google, "Google Play services in the container", GMS_DESCRIPTION,
                (button, checked) -> {
                    if (syncing) {
                        return;
                    }
                    onGmsToggled(checked);
                });
        gmsDescription = lastDescription;

        // Help
        LinearLayout help = section(root, "Help");
        navRow(help, "Copy debug log",
                "Copies recent errors so you can paste them into the chat when something goes wrong.",
                false, v -> copyDebugLog());

        TextView tip = text("Tip: leave a running app with the BG button, then press Home. "
                + "Do not swipe this app away in recents, and lock it there if your phone allows.",
                12, COLOR_MUTED, false);
        tip.setLineSpacing(dp(2), 1f);
        tip.setPadding(dp(4), dp(18), dp(4), 0);
        root.addView(tip);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(COLOR_BACKGROUND);
        frame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(buildStatusBar());
        return frame;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text("BG App Runner", 26, COLOR_TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        keepPill = text("", 12, COLOR_MUTED, true);
        keepPill.setGravity(Gravity.CENTER_VERTICAL);
        keepPill.setCompoundDrawablePadding(dp(6));
        titleRow.addView(keepPill);
        header.addView(titleRow);

        TextView subtitle = text("Run apps inside this one and keep them alive in the background.",
                14, COLOR_MUTED, false);
        subtitle.setPadding(0, dp(4), 0, 0);
        header.addView(subtitle);

        updatePill(KeepAliveService.isEnabled(this));
        return header;
    }

    /** The BlackBox manager: the big, obvious way to add, organise and configure apps. */
    private View buildManagerHero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{COLOR_HERO_START, COLOR_HERO_END});
        background.setCornerRadius(dp(20));
        hero.setBackground(background);
        if (Build.VERSION.SDK_INT >= 21) {
            hero.setElevation(dp(3));
        }
        hero.setClickable(true);
        hero.setOnClickListener(v -> openManager());

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(rounded(0x26FFFFFF, 14, 0, 0));
        tile.addView(new GridIconView(this), new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        top.addView(tile, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        titles.addView(text("BlackBox manager", 20, Color.WHITE, true));
        titles.addView(text("Install and manage your apps", 13, 0xCCFFFFFF, false));
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(top);

        TextView body = text("Install apps from your phone, create shortcuts, clear app data, "
                + "set a fake location and change settings.", 14, 0xE6FFFFFF, false);
        body.setLineSpacing(dp(2), 1f);
        body.setPadding(0, dp(14), 0, 0);
        hero.addView(body);

        TextView open = text("Open BlackBox manager  →", 15, COLOR_HERO_START, true);
        open.setGravity(Gravity.CENTER);
        open.setBackground(rounded(Color.WHITE, 14, 0, 0));
        open.setClickable(true);
        open.setOnClickListener(v -> openManager());
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        openParams.topMargin = dp(16);
        hero.addView(open, openParams);
        return hero;
    }

    private View buildAppsSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        appsTitle = overline("Your apps");
        head.addView(appsTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(tonalButton("+ Add APK", v -> pickApk()));
        LinearLayout.LayoutParams headParams = block(22);
        headParams.bottomMargin = dp(8);
        section.addView(head, headParams);

        LinearLayout card = groupCard();
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        card.addView(appList);
        section.addView(card, block(0));
        return section;
    }

    /** The message bar that floats at the bottom of the screen. */
    private View buildStatusBar() {
        statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        statusBar.setPadding(dp(16), dp(12), dp(8), dp(12));
        statusBar.setBackground(rounded(COLOR_SNACKBAR, 14, 0, 0));
        if (Build.VERSION.SDK_INT >= 21) {
            statusBar.setElevation(dp(8));
        }
        status = text("", 14, Color.WHITE, false);
        status.setLineSpacing(dp(2), 1f);
        statusBar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView dismiss = text("OK", 14, 0xFF90CAF9, true);
        dismiss.setPadding(dp(14), dp(6), dp(10), dp(6));
        statusBar.addView(dismiss);
        statusBar.setOnClickListener(v -> statusBar.setVisibility(View.GONE));
        statusBar.setVisibility(View.GONE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        params.setMargins(dp(16), 0, dp(16), dp(16));
        statusBar.setLayoutParams(params);
        return statusBar;
    }

    /** A titled group: a small heading on the background and an empty rounded card under it. */
    private LinearLayout section(LinearLayout root, String title) {
        LinearLayout.LayoutParams headingParams = block(22);
        headingParams.bottomMargin = dp(8);
        root.addView(overline(title), headingParams);
        LinearLayout card = groupCard();
        root.addView(card, block(0));
        return card;
    }

    private Switch switchRow(LinearLayout card, String title, String description,
                             CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(14), dp(13));
        row.setBackground(rowRipple());

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(medium(title, 16, COLOR_TEXT));
        TextView desc = text(description, 13, COLOR_MUTED, false);
        desc.setLineSpacing(dp(2), 1f);
        desc.setPadding(0, dp(3), 0, 0);
        labels.addView(desc);
        lastDescription = desc;
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch toggle = new Switch(this);
        toggle.setOnCheckedChangeListener(listener);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.leftMargin = dp(12);
        row.addView(toggle, toggleParams);

        // The whole row is a big touch target, not just the small switch.
        row.setOnClickListener(v -> {
            if (toggle.isEnabled()) {
                toggle.toggle();
            }
        });
        addRow(card, row);
        return toggle;
    }

    /** A tappable row that opens something. Returns its status chip, or null when it has none. */
    private TextView navRow(LinearLayout card, String title, String description, boolean withChip,
                            View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        row.setBackground(rowRipple());
        row.setClickable(true);
        row.setOnClickListener(listener);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(medium(title, 16, COLOR_TEXT));
        TextView desc = text(description, 13, COLOR_MUTED, false);
        desc.setLineSpacing(dp(2), 1f);
        desc.setPadding(0, dp(3), 0, 0);
        labels.addView(desc);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chip = null;
        if (withChip) {
            chip = text("", 12, COLOR_MUTED, true);
            chip.setPadding(dp(10), dp(4), dp(10), dp(4));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipParams.leftMargin = dp(10);
            row.addView(chip, chipParams);
        }
        TextView chevron = text("›", 26, COLOR_MUTED, false);
        chevron.setPadding(dp(8), 0, dp(4), 0);
        row.addView(chevron);

        addRow(card, row);
        return chip;
    }

    /** Adds a row to a card, with a thin divider above it unless it is the first one. */
    private void addRow(LinearLayout card, View row) {
        if (card.getChildCount() > 0) {
            card.addView(divider());
        }
        card.addView(row);
    }

    private LinearLayout groupCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(COLOR_CARD, 16, COLOR_BORDER, 1));
        if (Build.VERSION.SDK_INT >= 21) {
            card.setClipToOutline(true); // keeps row highlights inside the rounded corners
        }
        return card;
    }

    private LinearLayout.LayoutParams block(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        return params;
    }

    private TextView overline(String label) {
        TextView t = text(label, 12, COLOR_MUTED, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), 0, 0, 0);
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.leftMargin = dp(16);
        v.setLayoutParams(params);
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

    /** Slightly bolder than normal text, for row titles. */
    private TextView medium(String value, int sp, int color) {
        TextView t = text(value, sp, color, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView filledButton(String label, View.OnClickListener listener) {
        TextView b = text(label, 14, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        b.setBackground(rounded(COLOR_ACCENT, 12, COLOR_ACCENT, 0));
        b.setClickable(true);
        b.setOnClickListener(listener);
        return b;
    }

    private TextView tonalButton(String label, View.OnClickListener listener) {
        TextView b = text(label, 13, COLOR_ACCENT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(rounded(COLOR_ACCENT_SOFT, 12, COLOR_ACCENT_SOFT, 0));
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

    /** Touch feedback for a row that fills its card edge to edge. */
    private Drawable rowRipple() {
        if (Build.VERSION.SDK_INT >= 21) {
            return new RippleDrawable(ColorStateList.valueOf(0x1F1565C0), null,
                    new ColorDrawable(Color.WHITE));
        }
        return new ColorDrawable(Color.TRANSPARENT);
    }

    /** Round touch feedback for an icon-only button. */
    private Drawable circleRipple() {
        if (Build.VERSION.SDK_INT >= 21) {
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(Color.WHITE);
            return new RippleDrawable(ColorStateList.valueOf(0x22000000), null, mask);
        }
        return new ColorDrawable(Color.TRANSPARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Shows a message at the bottom of the screen. Messages ending in "..." stay until replaced. */
    private void showStatus(String message) {
        status.setText(message);
        statusBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideStatus);
        if (!message.endsWith("...")) {
            ui.postDelayed(hideStatus, STATUS_VISIBLE_MS);
        }
    }

    private void setStatus(String text) {
        ui.post(() -> showStatus(text));
    }

    private void toast(String text) {
        ui.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    /** The small "Keep-alive on/off" badge in the header. */
    private void updatePill(boolean on) {
        keepPill.setText(on ? "Keep-alive on" : "Keep-alive off");
        keepPill.setTextColor(on ? COLOR_SUCCESS : COLOR_MUTED);
        keepPill.setPadding(dp(10), dp(5), dp(12), dp(5));
        keepPill.setBackground(rounded(on ? COLOR_SUCCESS_SOFT : COLOR_NEUTRAL_SOFT, 14, 0, 0));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(on ? COLOR_SUCCESS : 0xFF9AA3AF);
        dot.setSize(dp(8), dp(8));
        keepPill.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
    }

    private void setChip(TextView chip, String label, boolean good) {
        if (chip == null) {
            return;
        }
        chip.setText(label);
        chip.setTextColor(good ? COLOR_SUCCESS : COLOR_WARN);
        chip.setBackground(rounded(good ? COLOR_SUCCESS_SOFT : COLOR_WARN_SOFT, 12, 0, 0));
    }

    // ---------------------------------------------------------------- Switch state

    private void syncSwitches() {
        syncing = true;
        keepSwitch.setChecked(KeepAliveService.isEnabled(this));
        updatePill(KeepAliveService.isEnabled(this));
        stayActiveSwitch.setChecked(KeepAliveService.isStayActiveEnabled(this));
        muteSwitch.setChecked(KeepAliveService.isMuteGuestEnabled(this));
        openWithSwitch.setChecked(isOpenWithEnabled());
        try {
            boolean gmsSupported = BlackBoxCore.get().isSupportGms();
            gmsSwitch.setEnabled(gmsSupported && !gmsBusy);
            gmsSwitch.setChecked(gmsSupported && BlackBoxCore.get().isInstallGms(USER_ID));
            gmsDescription.setText(gmsSupported ? GMS_DESCRIPTION
                    : "This phone has no Google Play services to copy, so this is unavailable.");
        } catch (Throwable ignored) {
            gmsSwitch.setEnabled(false);
        }
        boolean floatWanted = FloatingButtonService.isEnabled(this);
        boolean canDraw = FloatingButtonService.canDraw(this);
        floatSwitch.setChecked(floatWanted && canDraw);
        floatDescription.setText(floatWanted && !canDraw
                ? "Needs \"Display over other apps\". Turn this on and allow it."
                : FLOAT_DESCRIPTION);
        boolean silentOn = KeepAliveService.isSilentAudioEnabled(this);
        silentSwitch.setChecked(silentOn);
        silentDescription.setText(silentOn
                ? SILENT_DESCRIPTION + "\nStatus: " + KeepAliveService.silentAudioState(this)
                : SILENT_DESCRIPTION);
        syncing = false;
        syncPhoneChips();
        warnIfNotificationsOff();
    }

    /** Shows whether the two settings this app can read are already set the way it needs. */
    private void syncPhoneChips() {
        boolean exempt = true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            exempt = pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        setChip(batteryChip, exempt ? "Allowed" : "Not allowed", exempt);
        boolean notifications = notificationsEnabled();
        setChip(notificationChip, notifications ? "On" : "Blocked", notifications);
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
            showStatus("Notifications are blocked for this app, so the keep-alive notification and "
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

    private ComponentName openWithComponent() {
        return new ComponentName(this, OpenWithActivity.class);
    }

    private boolean isOpenWithEnabled() {
        try {
            return getPackageManager().getComponentEnabledSetting(openWithComponent())
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return true;
        }
    }

    private void setOpenWithEnabled(boolean enabled) {
        try {
            getPackageManager().setComponentEnabledSetting(openWithComponent(),
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            setStatus("Could not change the menu entry: " + t.getMessage());
        }
    }

    private void onGmsToggled(boolean checked) {
        if (gmsBusy) {
            return;
        }
        gmsBusy = true;
        gmsSwitch.setEnabled(false);
        setStatus(checked ? "Installing Google services. This can take a minute..."
                : "Removing Google services...");
        io.execute(() -> {
            String message;
            try {
                if (checked) {
                    InstallResult result = BlackBoxCore.get().installGms(USER_ID);
                    message = result.success
                            ? "Google services are installed in the container. Restart your apps."
                            : "Could not install Google services: " + result.msg;
                } else {
                    boolean removed = BlackBoxCore.get().uninstallGms(USER_ID);
                    message = removed ? "Google services removed." : "Could not remove Google services.";
                }
            } catch (Throwable t) {
                message = "Google services failed: " + t.getMessage();
            }
            final String text = message;
            ui.post(() -> {
                gmsBusy = false;
                setStatus(text);
                syncSwitches();
                refreshApps();
            });
        });
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

    // ---------------------------------------------------------------- BlackBox manager

    private void openManager() {
        try {
            startActivity(new Intent(this, WelcomeActivity.class));
        } catch (Throwable t) {
            toast("Could not open the BlackBox manager: " + t.getMessage());
        }
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
            // Names and icons are loaded here, off the main thread.
            PackageManager pm = getPackageManager();
            List<AppItem> items = new ArrayList<>();
            if (packages != null) {
                for (PackageInfo info : packages) {
                    String label = info.packageName;
                    Drawable icon = null;
                    try {
                        if (info.applicationInfo != null) {
                            label = String.valueOf(info.applicationInfo.loadLabel(pm));
                            icon = info.applicationInfo.loadIcon(pm);
                        }
                    } catch (Throwable ignored) {
                        // fall back to the package name and a letter badge
                    }
                    items.add(new AppItem(label, info.packageName, icon));
                }
            }
            ui.post(() -> renderApps(items));
        });
    }

    private void renderApps(List<AppItem> items) {
        appList.removeAllViews();
        appsTitle.setText(items.isEmpty() ? "Your apps" : "Your apps · " + items.size());
        if (items.isEmpty()) {
            appList.addView(emptyApps());
            return;
        }
        boolean first = true;
        for (AppItem item : items) {
            if (!first) {
                appList.addView(divider());
            }
            first = false;
            appList.addView(appRow(item));
        }
    }

    private View emptyApps() {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = text("No apps yet", 17, COLOR_TEXT, true);
        title.setGravity(Gravity.CENTER);
        empty.addView(title);

        TextView body = text("Add an APK file here, or open the BlackBox manager above to install "
                + "apps from your phone.", 13, COLOR_MUTED, false);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(dp(2), 1f);
        body.setPadding(0, dp(6), 0, dp(16));
        empty.addView(body);

        empty.addView(filledButton("Add APK", v -> pickApk()));
        return empty;
    }

    private View appRow(AppItem item) {
        final String pkg = item.pkg;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(6), dp(12));
        row.setBackground(rowRipple());
        row.setClickable(true);
        row.setOnClickListener(v -> launch(pkg));

        row.addView(appIcon(item), new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(14), 0, dp(8), 0);
        TextView name = medium(item.label, 16, COLOR_TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(name);
        TextView pkgView = text(pkg, 12, COLOR_MUTED, false);
        pkgView.setSingleLine(true);
        pkgView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        names.addView(pkgView);
        row.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(filledButton("Launch", v -> launch(pkg)));

        DotsView more = new DotsView(this);
        more.setBackground(circleRipple());
        more.setClickable(true);
        more.setContentDescription("More actions for " + item.label);
        more.setOnClickListener(v -> showAppMenu(v, item));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        moreParams.leftMargin = dp(2);
        row.addView(more, moreParams);
        return row;
    }

    /** The app's own icon, or a coloured letter when the icon could not be loaded. */
    private View appIcon(AppItem item) {
        if (item.icon != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(item.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return icon;
        }
        String initial = item.label.isEmpty() ? "?" : item.label.substring(0, 1).toUpperCase();
        TextView badge = text(initial, 18, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        int[] palette = {0xFF1E88E5, 0xFF8E24AA, 0xFF43A047, 0xFFF4511E, 0xFF00897B, 0xFF3949AB};
        badge.setBackground(rounded(palette[Math.abs(item.pkg.hashCode()) % palette.length], 12, 0, 0));
        return badge;
    }

    private void showAppMenu(View anchor, AppItem item) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Stop app");
        menu.getMenu().add(0, 2, 1, "Remove app…");
        menu.setOnMenuItemClickListener(entry -> {
            if (entry.getItemId() == 1) {
                stopApp(item.pkg);
            } else {
                confirmRemove(item);
            }
            return true;
        });
        menu.show();
    }

    private void confirmRemove(AppItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + item.label + "?")
                .setMessage("This deletes the app and all of its data from the container, including "
                        + "saves, logins and downloads. It cannot be undone.")
                .setPositiveButton("Remove", (dialog, which) -> remove(item.pkg))
                .setNegativeButton("Cancel", null)
                .show();
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

    /** The phone's "autostart / run in background" screen, or App info where there is none. */
    private void openAutostart() {
        openFirstThatWorks(new Intent().setComponent(new ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity")));
    }

    /** The phone's per-app battery saver screen, or App info where there is none. */
    private void openBatterySaver() {
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
                        new String[]{"logcat", "-d", "-v", "threadtime", "-t", "30000"});
                Pattern level = Pattern.compile("^\\S+\\s+\\S+\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s");
                StringBuilder out = new StringBuilder();
                final int[] hiddenCount = {0};
                int lines = 0;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    boolean skipTrace = false;
                    while ((line = reader.readLine()) != null) {
                        // Crashes of the copied Google apps repeat endlessly and bury everything else.
                        if (line.contains("AndroidRuntime")) {
                            if (line.contains("Process: com.google.android.gms")
                                    || line.contains("Process: com.android.vending")) {
                                skipTrace = true;
                                hiddenCount[0]++;
                            } else if (line.contains("FATAL EXCEPTION")) {
                                skipTrace = false;
                            }
                            if (skipTrace) {
                                continue;
                            }
                        } else {
                            skipTrace = false;
                        }
                        Matcher m = level.matcher(line);
                        boolean keep = line.contains("BlackBox") || line.contains("AndroidRuntime")
                                || line.contains("FATAL") || line.contains("SplashScreen")
                                || line.contains("StayActive") || line.contains("BgMute")
                                || line.contains("OpenWith") || line.contains("startActivityLocked")
                                || line.contains("AuthRedirect") || line.contains("Xal") || line.contains("xal")
                                || line.contains("BrowserLaunch") || line.contains("CustomTabs")
                                || line.contains("chromium") || line.contains("Hook in")
                                || line.contains("mojang") || line.contains("Minecraft") || line.contains("WebView")
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
                if (hiddenCount[0] > 0) {
                    out.insert(0, "(" + hiddenCount[0] + " Google service crash traces hidden)\n");
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
                    showStatus(count == 0
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
            try {
                d.append("gmsInContainer=").append(BlackBoxCore.get().isInstallGms(USER_ID))
                        .append("/gmsOnPhone=").append(BlackBoxCore.get().isSupportGms()).append(" ");
            } catch (Throwable ignored) {
                // not available
            }
            d.append(" stayActive=").append(KeepAliveService.isStayActiveEnabled(this))
                    .append(" muteGuest=").append(KeepAliveService.isMuteGuestEnabled(this));
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

    // ---------------------------------------------------------------- Small drawn icons

    /** Four rounded squares, one of them faded: "a grid of apps, with room for one more". */
    private static final class GridIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridIconView(Context context) {
            super(context);
            paint.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float side = Math.min(getWidth(), getHeight());
            float gap = side * 0.14f;
            float cell = (side - gap) / 2f;
            float radius = cell * 0.3f;
            float left = (getWidth() - side) / 2f;
            float top = (getHeight() - side) / 2f;
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 2; col++) {
                    paint.setAlpha(row == 1 && col == 1 ? 110 : 255);
                    float x = left + col * (cell + gap);
                    float y = top + row * (cell + gap);
                    canvas.drawRoundRect(x, y, x + cell, y + cell, radius, radius, paint);
                }
            }
        }
    }

    /** Three vertical dots: the usual "more actions" icon. */
    private static final class DotsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DotsView(Context context) {
            super(context);
            paint.setColor(COLOR_MUTED);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * 0.05f;
            float step = radius * 4.4f;
            canvas.drawCircle(cx, cy - step, radius, paint);
            canvas.drawCircle(cx, cy, radius, paint);
            canvas.drawCircle(cx, cy + step, radius, paint);
        }
    }
}
