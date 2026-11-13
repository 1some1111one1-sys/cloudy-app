package top.niunaijun.blackboxa.runner;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * purpose. The look is calm liquid glass: a slowly moving blue backdrop with frosted panels on it.
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

    private LinearLayout content;
    private TextView keepPill;
    private TextView appsTitle;
    private LinearLayout appList;
    private GlassSwitch keepSwitch;
    private GlassSwitch floatSwitch;
    private GlassSwitch silentSwitch;
    private GlassSwitch stayActiveSwitch;
    private GlassSwitch muteSwitch;
    private GlassSwitch gmsSwitch;
    private GlassSwitch openWithSwitch;
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

        // The backdrop runs behind the status bar and the navigation bar, so both are see-through
        // and the content makes room for them (see applyInsets).
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

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
        content = root;

        root.addView(buildHeader());
        root.addView(buildManagerHero(), block(20));
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
                12, Glass.TEXT3, false);
        tip.setLineSpacing(dp(2), 1f);
        tip.setPadding(dp(4), dp(18), dp(4), 0);
        root.addView(tip);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout frame = new FrameLayout(this);
        // The live backdrop sits behind everything.
        frame.addView(new GlassBackground(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(buildStatusBar());
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        return frame;
    }

    /** Keeps the content clear of the status bar, the navigation bar and any camera cut-out. */
    private void applyInsets(int left, int top, int right, int bottom) {
        content.setPadding(dp(16) + left, dp(20) + top, dp(16) + right, dp(96) + bottom);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) statusBar.getLayoutParams();
        params.setMargins(dp(16) + left, 0, dp(16) + right, dp(16) + bottom);
        statusBar.setLayoutParams(params);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView tag = mono("BG · App · Runner", 10, Glass.CYAN);
        tag.setLetterSpacing(0.18f);
        titles.addView(tag);
        TextView title = medium("BG App Runner", 28, Glass.TEXT);
        title.setPadding(0, dp(4), 0, 0);
        titles.addView(title);
        titleRow.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        keepPill = mono("", 10, Glass.TEXT2);
        keepPill.setGravity(Gravity.CENTER_VERTICAL);
        keepPill.setCompoundDrawablePadding(dp(7));
        titleRow.addView(keepPill);
        header.addView(titleRow);

        TextView subtitle = text("Run apps inside this one and keep them alive in the background.",
                14, Glass.TEXT2, false);
        subtitle.setLineSpacing(dp(1), 1f);
        subtitle.setPadding(0, dp(6), 0, 0);
        header.addView(subtitle);

        updatePill(KeepAliveService.isEnabled(this));
        return header;
    }

    /** The BlackBox manager: the big, obvious way to add, organise and configure apps. */
    private View buildManagerHero() {
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(GlassPanel.hero(dp(26), dp(1)));
        if (Build.VERSION.SDK_INT >= 21) {
            hero.setClipToOutline(true);
        }
        hero.setForeground(Glass.pressable(null, dp(26), 0x2E7CE4FF));
        hero.setClickable(true);
        hero.setOnClickListener(v -> openManager());

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(18), dp(20), dp(18), dp(18));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout tile = new FrameLayout(this);
        GradientDrawable tileFace = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x38FFFFFF, 0x0DFFFFFF});
        tileFace.setCornerRadius(dp(18));
        tileFace.setStroke(dp(1), 0x47FFFFFF);
        tile.setBackground(tileFace);
        tile.addView(new GridIconView(this), new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        top.addView(tile, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        titles.addView(medium("BlackBox manager", 22, Glass.TEXT));
        TextView caption = mono("Install · Organise · Configure", 10, Glass.CYAN);
        caption.setPadding(0, dp(4), 0, 0);
        titles.addView(caption);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(top);

        TextView body = text("Install apps from your phone, create shortcuts, clear app data, "
                + "set a fake location and change settings.", 14, 0xFFD6DEF0, false);
        body.setLineSpacing(dp(2), 1f);
        body.setPadding(0, dp(14), 0, 0);
        column.addView(body);

        TextView open = text("Open BlackBox manager  →", 15, Glass.INK, true);
        open.setGravity(Gravity.CENTER);
        open.setBackground(Glass.pressable(Glass.lightButton(dp(16)), dp(16), 0x44FFFFFF));
        open.setClickable(true);
        open.setOnClickListener(v -> openManager());
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        openParams.topMargin = dp(16);
        column.addView(open, openParams);

        hero.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Four small corner marks: the quiet sci-fi touch.
        hero.addView(new BracketsView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        LinearLayout.LayoutParams headParams = block(24);
        headParams.bottomMargin = dp(9);
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
        statusBar.setBackground(GlassPanel.solid(dp(18), dp(1)));
        status = text("", 14, Glass.TEXT, false);
        status.setLineSpacing(dp(2), 1f);
        statusBar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView dismiss = text("OK", 14, Glass.CYAN, true);
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

    /** A titled group: a small heading on the backdrop and an empty glass panel under it. */
    private LinearLayout section(LinearLayout root, String title) {
        LinearLayout.LayoutParams headingParams = block(24);
        headingParams.bottomMargin = dp(9);
        root.addView(overline(title), headingParams);
        LinearLayout card = groupCard();
        root.addView(card, block(0));
        return card;
    }

    private GlassSwitch switchRow(LinearLayout card, String title, String description,
                                  CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(14), dp(13));
        row.setBackground(Glass.rowRipple());

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(medium(title, 16, Glass.TEXT));
        TextView desc = text(description, 13, Glass.TEXT2, false);
        desc.setLineSpacing(dp(2), 1f);
        desc.setPadding(0, dp(3), 0, 0);
        labels.addView(desc);
        lastDescription = desc;
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final GlassSwitch toggle = new GlassSwitch(this);
        toggle.setContentDescription(title);
        toggle.setOnCheckedChangeListener(listener);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleParams.leftMargin = dp(12);
        row.addView(toggle, toggleParams);

        // The whole row is a big touch target, not just the switch.
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
        row.setBackground(Glass.rowRipple());
        row.setClickable(true);
        row.setOnClickListener(listener);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(medium(title, 16, Glass.TEXT));
        TextView desc = text(description, 13, Glass.TEXT2, false);
        desc.setLineSpacing(dp(2), 1f);
        desc.setPadding(0, dp(3), 0, 0);
        labels.addView(desc);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chip = null;
        if (withChip) {
            chip = text("", 11, Glass.TEXT2, true);
            chip.setPadding(dp(10), dp(4), dp(10), dp(4));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipParams.leftMargin = dp(10);
            row.addView(chip, chipParams);
        }
        TextView chevron = text("›", 26, Glass.TEXT3, false);
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
        card.setBackground(GlassPanel.card(dp(22), dp(1)));
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

    /** A small heading in the quiet monospace lettering, with a short cyan dash in front. */
    private TextView overline(String label) {
        TextView t = mono(label, 11, Glass.TEXT2);
        t.setPadding(dp(4), 0, 0, 0);
        t.setCompoundDrawablePadding(dp(8));
        GradientDrawable dash = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Glass.CYAN, 0x007CE4FF});
        dash.setSize(dp(14), Math.max(1, dp(1)));
        t.setCompoundDrawablesWithIntrinsicBounds(dash, null, null, null);
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
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

    /** Slightly bolder than normal text, for titles and row names. */
    private TextView medium(String value, int sp, int color) {
        TextView t = text(value, sp, color, false);
        t.setTypeface(Glass.medium());
        return t;
    }

    /** Small capital letters in a monospace face, for labels and status words. */
    private TextView mono(String value, int sp, int color) {
        TextView t = text(value, sp, color, false);
        t.setTypeface(Glass.mono());
        t.setAllCaps(true);
        t.setLetterSpacing(0.12f);
        return t;
    }

    /** The bright ice-blue button. */
    private TextView filledButton(String label, View.OnClickListener listener) {
        TextView b = text(label, 14, Glass.INK, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(9), dp(16), dp(9));
        b.setBackground(Glass.pressable(Glass.lightButton(dp(13)), dp(13), 0x44FFFFFF));
        b.setClickable(true);
        b.setOnClickListener(listener);
        return b;
    }

    /** The small outlined cyan button. */
    private TextView tonalButton(String label, View.OnClickListener listener) {
        TextView b = text(label, 13, Glass.CYAN, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(Glass.pressable(Glass.shape(0x1F7CE4FF, 0x477CE4FF, dp(14), dp(1)),
                dp(14), 0x337CE4FF));
        b.setClickable(true);
        b.setOnClickListener(listener);
        return b;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int strokeColor, int strokeDp) {
        return Glass.shape(fill, strokeColor, dp(radiusDp), dp(strokeDp));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Shows a message at the bottom of the screen. Messages ending in "..." stay until replaced. */
    private void showStatus(String message) {
        boolean wasHidden = statusBar.getVisibility() != View.VISIBLE;
        status.setText(message);
        statusBar.setVisibility(View.VISIBLE);
        if (wasHidden) {
            statusBar.animate().cancel();
            statusBar.setAlpha(0f);
            statusBar.setTranslationY(dp(12));
            statusBar.animate().alpha(1f).translationY(0f).setDuration(180).start();
        }
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
        keepPill.setTextColor(on ? Glass.TEXT : Glass.TEXT2);
        keepPill.setPadding(dp(11), dp(6), dp(12), dp(6));
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x1FAAC8FF, 0x0AAAC8FF});
        face.setCornerRadius(dp(99));
        face.setStroke(dp(1), 0x2EFFFFFF);
        keepPill.setBackground(face);
        keepPill.setCompoundDrawablesWithIntrinsicBounds(
                new DotDrawable(on ? Glass.MINT : 0xFF8794AE, on, dp(16)), null, null, null);
    }

    private void setChip(TextView chip, String label, boolean good) {
        if (chip == null) {
            return;
        }
        int color = good ? Glass.MINT : Glass.AMBER;
        chip.setText(label);
        chip.setTextColor(color);
        chip.setBackground(Glass.shape(Glass.withAlpha(color, 0.12f), Glass.withAlpha(color, 0.30f),
                dp(12), dp(1)));
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
        empty.setPadding(dp(24), dp(26), dp(24), dp(26));

        TextView title = medium("No apps yet", 17, Glass.TEXT);
        title.setGravity(Gravity.CENTER);
        empty.addView(title);

        TextView body = text("Add an APK file here, or open the BlackBox manager above to install "
                + "apps from your phone.", 13, Glass.TEXT2, false);
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
        row.setBackground(Glass.rowRipple());
        row.setClickable(true);
        row.setOnClickListener(v -> launch(pkg));

        row.addView(appIcon(item), new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(14), 0, dp(8), 0);
        TextView name = medium(item.label, 16, Glass.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(name);
        TextView pkgView = text(pkg, 11, Glass.TEXT3, false);
        pkgView.setTypeface(Glass.mono());
        pkgView.setSingleLine(true);
        pkgView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        pkgView.setPadding(0, dp(2), 0, 0);
        names.addView(pkgView);
        row.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(filledButton("Launch", v -> launch(pkg)));

        DotsView more = new DotsView(this);
        more.setBackground(Glass.circleRipple());
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
        int[][] palette = {
                {0xFF1E88E5, 0xFF3949AB}, {0xFF8E24AA, 0xFF5E35B1}, {0xFF43A047, 0xFF00897B},
                {0xFFF4511E, 0xFFD81B60}, {0xFF00897B, 0xFF0288D1}, {0xFF3949AB, 0xFF7E57C2}};
        int[] colors = palette[Math.abs(item.pkg.hashCode()) % palette.length];
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        face.setCornerRadius(dp(14));
        badge.setBackground(face);
        return badge;
    }

    /** The small glass menu under the three dots of an app row. */
    private void showAppMenu(View anchor, AppItem item) {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, dp(6), 0, dp(6));
        menu.setBackground(GlassPanel.solid(dp(16), dp(1)));
        if (Build.VERSION.SDK_INT >= 21) {
            menu.setClipToOutline(true);
        }

        final PopupWindow popup = new PopupWindow(menu, dp(190),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);

        menu.addView(menuRow("Stop app", Glass.TEXT, () -> {
            popup.dismiss();
            stopApp(item.pkg);
        }));
        menu.addView(menuRow("Remove app…", Glass.ROSE, () -> {
            popup.dismiss();
            confirmRemove(item);
        }));

        menu.setAlpha(0f);
        menu.setTranslationY(-dp(6));
        popup.showAsDropDown(anchor, 0, -dp(8), Gravity.END);
        menu.animate().alpha(1f).translationY(0f).setDuration(160).start();
    }

    private TextView menuRow(String label, int color, Runnable action) {
        TextView row = medium(label, 15, color);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(46));
        row.setPadding(dp(18), 0, dp(18), 0);
        row.setBackground(Glass.rowRipple());
        row.setClickable(true);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** Asking before something that cannot be undone, in a glass dialog. */
    private void confirmRemove(AppItem item) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(22), dp(16), dp(14));
        panel.setBackground(GlassPanel.solid(dp(26), dp(1)));
        if (Build.VERSION.SDK_INT >= 21) {
            panel.setClipToOutline(true);
        }

        panel.addView(medium("Remove " + item.label + "?", 20, Glass.TEXT));
        TextView message = text("This deletes the app and all of its data from the container, including "
                + "saves, logins and downloads. It cannot be undone.", 14, Glass.TEXT2, false);
        message.setLineSpacing(dp(2), 1f);
        message.setPadding(0, dp(10), dp(6), dp(18));
        panel.addView(message);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView cancel = text("Cancel", 14, Glass.TEXT2, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(16), dp(10), dp(16), dp(10));
        cancel.setBackground(Glass.pressable(null, dp(14), 0x22FFFFFF));
        cancel.setClickable(true);
        cancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(cancel);
        TextView confirm = text("Remove", 14, Glass.ROSE, true);
        confirm.setGravity(Gravity.CENTER);
        confirm.setPadding(dp(18), dp(10), dp(18), dp(10));
        confirm.setBackground(Glass.pressable(
                Glass.shape(Glass.withAlpha(Glass.ROSE, 0.14f), Glass.withAlpha(Glass.ROSE, 0.40f),
                        dp(14), dp(1)), dp(14), 0x33FF8A9B));
        confirm.setClickable(true);
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            remove(item.pkg);
        });
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        confirmParams.leftMargin = dp(8);
        buttons.addView(confirm, confirmParams);
        panel.addView(buttons);

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(40));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(0.62f);
        }
        dialog.show();
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

    /** Words that make a log line worth keeping even when it is not a warning or an error. */
    private static final String[] LOG_KEYWORDS = {
            "BlackBox", "AndroidRuntime", "FATAL", "SplashScreen", "StayActive", "BgMute", "OpenWith",
            "startActivityLocked", "AuthRedirect", "Xal", "xal", "BrowserLaunch", "CustomTabs", "chromium",
            "Hook in", "mojang", "Minecraft", "WebView", "CrashMonitor", "SimpleCrashFix", "BProcessManager",
            "ActivityStack", "App Died", "CatchMeIfYouCan", "appstatelogger", "UnsatisfiedLink", "dlopen",
            "Fatal signal", "SIGSEGV", "SIGABRT", "tombstone", "libGTASA", "rockstar",
            // Google sign-in
            "IAccountManagerProxy", "GoogleSignatureVerifier", "GooglePlayServicesUtil", "GoogleApiManager",
            "Not allowed to bind", "GLSActivity", "GLSUser", "GoogleAuth"};

    /** Lines that are never collapsed as repeats: they are the story of a crash or of a start-up. */
    private static final String[] LOG_NEVER_HIDE = {
            "AndroidRuntime", "BlackBoxCrash", "BlackBoxAccounts", "CrashMonitor", "SimpleCrashFix",
            "Fatal signal", "DEBUG ", "tombstone", "UnsatisfiedLink", "OpenWith", "startActivityLocked",
            "BProcessManager", "beforeCreateApplication", "SplashScreen", "StayActive", "BgMute",
            "ActivityStack", "CatchMeIfYouCan", "appstatelogger"};

    private static boolean containsAny(String line, String[] words) {
        for (String word : words) {
            if (line.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /**
     * Copies a report to the clipboard: settings, how the container's processes ended, the crash
     * reports it saved, the apps inside it and the recent log (warnings, errors and its own events).
     */
    private void copyDebugLog() {
        setStatus("Collecting log...");
        io.execute(() -> {
            try {
                final DebugReport.Exits exits = DebugReport.recentExits(this);
                final Set<Integer> tailPids = new HashSet<>(exits.guestPids);
                final Map<Integer, ArrayDeque<String>> tails = new HashMap<>();
                final Map<String, Integer> seenTail = new HashMap<>();
                final Map<String, Integer> seen = new HashMap<>();

                Process process = Runtime.getRuntime().exec(
                        new String[]{"logcat", "-d", "-v", "threadtime", "-t", "60000"});
                Pattern format = Pattern.compile("^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+(\\d+)\\s+\\d+\\s+"
                        + "([VDIWEF])\\s+(.*?)\\s*:\\s?(.*)$");
                StringBuilder out = new StringBuilder();
                int hiddenRepeats = 0;
                int hiddenGoogle = 0;
                int googleTraces = 0;
                int lines = 0;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    boolean skipTrace = false;
                    while ((line = reader.readLine()) != null) {
                        // Crashes of the copied Google apps repeat endlessly and bury everything else:
                        // the first few are kept in full, the rest are hidden.
                        if (line.contains("AndroidRuntime")) {
                            if (line.contains("Process: com.google.android.gms")
                                    || line.contains("Process: com.android.vending")) {
                                googleTraces++;
                                skipTrace = googleTraces > 3;
                                if (skipTrace) {
                                    hiddenGoogle++;
                                }
                            } else if (line.contains("FATAL EXCEPTION")) {
                                skipTrace = false;
                            }
                            if (skipTrace) {
                                continue;
                            }
                        } else {
                            skipTrace = false;
                        }
                        Matcher m = format.matcher(line);
                        if (!m.matches()) {
                            continue;
                        }
                        int pid = Integer.parseInt(m.group(1));
                        char level = m.group(2).charAt(0);
                        String tag = m.group(3);
                        String message = m.group(4);
                        boolean never = containsAny(line, LOG_NEVER_HIDE);

                        // The last lines of the container process that just ended, whatever their level.
                        if (tailPids.contains(pid)) {
                            String tailKey = pid + tag + "|" + clip(message, 60);
                            Integer n = seenTail.get(tailKey);
                            n = n == null ? 1 : n + 1;
                            seenTail.put(tailKey, n);
                            if (n <= 3 || never) {
                                ArrayDeque<String> tail = tails.get(pid);
                                if (tail == null) {
                                    tail = new ArrayDeque<>();
                                    tails.put(pid, tail);
                                }
                                if (tail.size() >= 80) {
                                    tail.removeFirst();
                                }
                                tail.addLast(clip(line, 220));
                            }
                        }

                        boolean keep = level == 'W' || level == 'E' || level == 'F'
                                || containsAny(line, LOG_KEYWORDS);
                        if (!keep) {
                            continue;
                        }
                        if (!never) {
                            String key = tag + "|" + clip(message, 80);
                            Integer n = seen.get(key);
                            n = n == null ? 1 : n + 1;
                            seen.put(key, n);
                            if (n > 4) {
                                hiddenRepeats++;
                                continue;
                            }
                        }
                        out.append(clip(line, 400)).append('\n');
                        lines++;
                        if (out.length() > 60000) {
                            out.delete(0, out.length() - 45000);
                        }
                    }
                }

                StringBuilder tailText = new StringBuilder();
                for (int pid : exits.guestPids) {
                    ArrayDeque<String> tail = tails.get(pid);
                    if (tail == null || tail.isEmpty()) {
                        continue;
                    }
                    tailText.append("Last log lines of the container process that ended (pid ")
                            .append(pid).append("):\n");
                    for (String l : tail) {
                        tailText.append("  ").append(l).append('\n');
                    }
                    tailText.append('\n');
                }

                final StringBuilder text = new StringBuilder();
                text.append("BG App Runner log, Android ").append(Build.VERSION.RELEASE)
                        .append(", ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        .append(", 64-bit host: ").append(BlackBoxCore.is64Bit()).append('\n')
                        .append(diagnostics()).append("\n\n")
                        .append(exits.text).append('\n')
                        .append(DebugReport.crashReports(this)).append('\n')
                        .append(DebugReport.guestApps(this)).append('\n')
                        .append(tailText);
                text.append("Log lines (warnings, errors and the container's own events; repeats hidden):\n");
                if (hiddenGoogle > 0) {
                    text.append("(").append(hiddenGoogle).append(" Google service crash lines hidden)\n");
                }
                if (hiddenRepeats > 0) {
                    text.append("(").append(hiddenRepeats).append(" repeated lines hidden)\n");
                }
                text.append(out);
                final int count = lines;
                ui.post(() -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("BG App Runner log", text.toString()));
                    }
                    showStatus(count == 0 && exits.guestPids.isEmpty()
                            ? "The log is empty. Launch the app first, then try again."
                            : "Copied the debug report. Paste it into your chat with Claude.");
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

    // ---------------------------------------------------------------- Small drawn pieces

    /** Four rounded squares, one of them faded: "a grid of apps, with room for one more". */
    private static final class GridIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridIconView(Context context) {
            super(context);
            paint.setColor(0xFFEAFBFF);
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
                    paint.setAlpha(row == 1 && col == 1 ? 115 : 255);
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
            paint.setColor(Glass.TEXT2);
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

    /** Four thin corner marks that frame a panel, a little like a viewfinder. */
    private static final class BracketsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF arc = new RectF();
        private final float margin;

        BracketsView(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            margin = 10f * density;
            float length = 14f * density;
            float radius = 6f * density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, 1.5f * density));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xBF7CE4FF);
            // The top left mark; the other three are this one mirrored.
            path.moveTo(0, length);
            path.lineTo(0, radius);
            arc.set(0, 0, radius * 2f, radius * 2f);
            path.arcTo(arc, 180f, 90f, false);
            path.lineTo(length, 0);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            mark(canvas, margin, margin, 1f, 1f);
            mark(canvas, w - margin, margin, -1f, 1f);
            mark(canvas, margin, h - margin, 1f, -1f);
            mark(canvas, w - margin, h - margin, -1f, -1f);
        }

        private void mark(Canvas canvas, float x, float y, float sx, float sy) {
            int save = canvas.save();
            canvas.translate(x, y);
            canvas.scale(sx, sy);
            canvas.drawPath(path, paint);
            canvas.restoreToCount(save);
        }
    }

    /** A small round light: a soft glow with a bright dot in the middle (or just a dot). */
    private static final class DotDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private final boolean glow;
        private final int size;

        DotDrawable(int color, boolean glow, int size) {
            this.color = color;
            this.glow = glow;
            this.size = size;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = size / 2f;
            if (glow) {
                paint.setShader(new RadialGradient(cx, cy, r,
                        new int[]{Glass.withAlpha(color, 0.6f), color & 0x00FFFFFF},
                        null, Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, r, paint);
                paint.setShader(null);
            }
            paint.setColor(color);
            canvas.drawCircle(cx, cy, size * 0.22f, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
