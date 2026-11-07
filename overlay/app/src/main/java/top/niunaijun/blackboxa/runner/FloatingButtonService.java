package top.niunaijun.blackboxa.runner;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Shows a small draggable button on top of everything while a virtual app is on screen.
 * Tap it to keep the virtual app running in the background and return to the home screen.
 * Hold it for a moment to hide it.
 */
public class FloatingButtonService extends Service {

    private static final String PREFS = "bg_runner_prefs";
    private static final String KEY_ENABLED = "floating_enabled";
    private static final String KEY_HINT_SHOWN = "floating_hint_shown";
    private static final long POLL_MS = 1500;
    private static final long LONG_PRESS_MS = 700;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            check();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private View button;
    private boolean dismissed;
    private Pattern guestProcess;

    // ---------------------------------------------------------------- Static helpers

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean canDraw(Context context) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
    }

    public static void start(Context context) {
        context.startService(new Intent(context, FloatingButtonService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, FloatingButtonService.class));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- Service

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        guestProcess = Pattern.compile("^" + Pattern.quote(getPackageName()) + ":p\\d+$");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(poll);
        handler.post(poll);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poll);
        removeButton();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- Visibility

    private void check() {
        if (!isEnabled(this) || !canDraw(this)) {
            removeButton();
            stopSelf();
            return;
        }
        boolean visible = guestOnScreen();
        if (!visible) {
            dismissed = false;
        }
        if (visible && button == null && !dismissed) {
            addButton();
        } else if (!visible && button != null) {
            removeButton();
        }
    }

    /**
     * A virtual app is on screen when one of its stub processes is at foreground importance
     * while this app's own process is not (so the BG App Runner screens do not count).
     */
    private boolean guestOnScreen() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes == null) {
                return false;
            }
            boolean guestForeground = false;
            boolean hostForeground = false;
            for (ActivityManager.RunningAppProcessInfo p : processes) {
                boolean foreground = p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                if (p.processName.equals(getPackageName())) {
                    hostForeground = foreground;
                } else if (foreground && guestProcess.matcher(p.processName).matches()) {
                    guestForeground = true;
                }
            }
            return guestForeground && !hostForeground;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- Button

    private void addButton() {
        try {
            int size = dp(52);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            params = new WindowManager.LayoutParams(size, size, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = dp(12);
            params.y = dp(140);

            TextView view = new TextView(this);
            view.setText("BG");
            view.setTextColor(Color.WHITE);
            view.setTextSize(14);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setGravity(Gravity.CENTER);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(0xDD1E88E5);
            background.setStroke(dp(2), Color.WHITE);
            view.setBackground(background);
            view.setOnTouchListener(new DragListener());

            windowManager.addView(view, params);
            button = view;
            showHintOnce();
        } catch (Throwable t) {
            button = null;
        }
    }

    private void removeButton() {
        if (button != null) {
            try {
                windowManager.removeView(button);
            } catch (Throwable ignored) {
                // already gone
            }
            button = null;
        }
    }

    private void showHintOnce() {
        SharedPreferences p = prefs(this);
        if (!p.getBoolean(KEY_HINT_SHOWN, false)) {
            p.edit().putBoolean(KEY_HINT_SHOWN, true).apply();
            Toast.makeText(this, "Tap BG to keep this app running in the background. "
                    + "Hold it to hide the button. Drag it to move.", Toast.LENGTH_LONG).show();
        }
    }

    /** Keep the virtual apps alive, then go to the home screen so their screen closes. */
    private void keepInBackground() {
        String note = "Keeping the app running in the background.";
        try {
            KeepAliveService.start(this);
        } catch (Throwable t) {
            note = "Could not start keep-alive: " + t.getMessage();
        }
        // Tell the virtual apps not to react when their screen goes away (see KeepAliveService).
        KeepAliveService.setStayActiveFlag(this, true);
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Throwable ignored) {
            // the user can press Home themselves
        }
        Toast.makeText(this, note, Toast.LENGTH_LONG).show();
        dismissed = true;
        removeButton();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class DragListener implements View.OnTouchListener {
        private int startX;
        private int startY;
        private float touchX;
        private float touchY;
        private boolean moved;
        private long downTime;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x;
                    startY = params.y;
                    touchX = event.getRawX();
                    touchY = event.getRawY();
                    moved = false;
                    downTime = event.getEventTime();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - touchX;
                    float dy = event.getRawY() - touchY;
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        moved = true;
                    }
                    if (moved) {
                        params.x = startX + (int) dx;
                        params.y = startY + (int) dy;
                        try {
                            windowManager.updateViewLayout(button, params);
                        } catch (Throwable ignored) {
                            // view was removed
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        if (event.getEventTime() - downTime >= LONG_PRESS_MS) {
                            dismissed = true;
                            removeButton();
                            Toast.makeText(FloatingButtonService.this,
                                    "Button hidden. It returns the next time a virtual app opens.",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            keepInBackground();
                        }
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
