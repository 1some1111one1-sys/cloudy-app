package top.niunaijun.blackboxa.runner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Shows up in Android's "Open with" list. It finds the apps inside the container that can open
 * the file, lets the user pick one if there are several, copies the file where the container
 * apps can read it, and opens it there.
 */
public class OpenWithActivity extends Activity {
    private static final String TAG = "OpenWith";
    private static final int USER_ID = 0;
    private static final long OLD_COPY_MS = 24L * 60 * 60 * 1000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Intent original;
    private String type;

    private static class Choice {
        ComponentName component;
        String label;
        Drawable icon;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        original = getIntent();
        Uri data = original == null ? null : original.getData();
        if (data == null) {
            finish();
            return;
        }
        io.execute(this::findApps);
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- Finding apps

    private void findApps() {
        try {
            Uri data = original.getData();
            type = original.getType();
            if (type == null) {
                try {
                    type = getContentResolver().getType(data);
                } catch (Throwable ignored) {
                    // unknown type
                }
            }
            Intent probe = new Intent(Intent.ACTION_VIEW);
            probe.setDataAndType(data, type);
            probe.addCategory(Intent.CATEGORY_DEFAULT);
            List<ResolveInfo> matches = BlackBoxCore.getBPackageManager().queryIntentActivities(
                    probe, PackageManager.GET_META_DATA, type, USER_ID);

            PackageManager pm = getPackageManager();
            final List<Choice> choices = new ArrayList<>();
            if (matches != null) {
                for (ResolveInfo match : matches) {
                    ActivityInfo info = match.activityInfo;
                    if (info == null) {
                        continue;
                    }
                    Choice choice = new Choice();
                    choice.component = new ComponentName(info.packageName, info.name);
                    choice.label = label(info, pm);
                    try {
                        choice.icon = info.applicationInfo.loadIcon(pm);
                    } catch (Throwable ignored) {
                        // no icon
                    }
                    choices.add(choice);
                }
            }
            ui.post(() -> show(choices));
        } catch (Throwable t) {
            Log.w(TAG, "could not look for apps", t);
            ui.post(() -> fail("Could not look for apps in the container: " + t.getMessage()));
        }
    }

    private static String label(ActivityInfo info, PackageManager pm) {
        String app = info.packageName;
        try {
            app = String.valueOf(info.applicationInfo.loadLabel(pm));
        } catch (Throwable ignored) {
            // keep the package name
        }
        try {
            String screen = String.valueOf(info.loadLabel(pm));
            if (!screen.isEmpty() && !screen.equals(app) && !screen.equals(info.name)) {
                return app + " (" + screen + ")";
            }
        } catch (Throwable ignored) {
            // app name only
        }
        return app;
    }

    // ---------------------------------------------------------------- Choosing

    private void show(List<Choice> choices) {
        if (choices.isEmpty()) {
            fail("No app in the container can open this file. Add one in BG App Runner first.");
            return;
        }
        if (choices.size() == 1) {
            open(choices.get(0));
            return;
        }
        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return choices.size();
            }

            @Override
            public Object getItem(int position) {
                return choices.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Choice choice = choices.get(position);
                LinearLayout row = new LinearLayout(OpenWithActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(12), dp(20), dp(12));
                ImageView icon = new ImageView(OpenWithActivity.this);
                if (choice.icon != null) {
                    icon.setImageDrawable(choice.icon);
                }
                row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                TextView name = new TextView(OpenWithActivity.this);
                name.setText(choice.label);
                name.setTextSize(16);
                name.setPadding(dp(16), 0, 0, 0);
                row.addView(name);
                return row;
            }
        };
        new AlertDialog.Builder(this)
                .setTitle("Open with (in the container)")
                .setAdapter(adapter, (dialog, which) -> open(choices.get(which)))
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- Opening

    private void open(Choice choice) {
        Toast.makeText(this, "Opening in " + choice.label + "...", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                Uri copy = copyToShared(original.getData());
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(copy, type != null ? type : getContentResolver().getType(copy));
                intent.setComponent(choice.component);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Log.d(TAG, "opening " + copy + " in " + choice.component.flattenToShortString());
                ui.post(() -> {
                    try {
                        BlackBoxCore.get().startActivity(intent, USER_ID);
                    } catch (Throwable t) {
                        Log.w(TAG, "could not start the app", t);
                        Toast.makeText(this, "Could not open the app: " + t.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                    finish();
                });
            } catch (Throwable t) {
                Log.w(TAG, "could not copy the file", t);
                ui.post(() -> fail("Could not prepare the file: " + t.getMessage()));
            }
        });
    }

    private void fail(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    /** Copies the file into this app's cache and returns an address the container apps can read. */
    private Uri copyToShared(Uri source) throws Exception {
        File root = OpenWithProvider.root(this);
        deleteOld(root);
        String id = UUID.randomUUID().toString();
        File dir = new File(root, id);
        if (!dir.mkdirs()) {
            throw new java.io.IOException("cannot create " + dir);
        }
        String name = safeName(displayName(source));
        File target = new File(dir, name);
        try (InputStream in = getContentResolver().openInputStream(source);
             OutputStream out = new java.io.FileOutputStream(target)) {
            if (in == null) {
                throw new java.io.IOException("cannot read the file");
            }
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
        return new Uri.Builder().scheme("content").authority(getPackageName() + ".openwith")
                .appendPath(id).appendPath(name).build();
    }

    private String displayName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getString(0);
                }
            } catch (Throwable ignored) {
                // use the last part of the address
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    private static String safeName(String name) {
        String cleaned = name == null ? "file" : name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            cleaned = "file";
        }
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }

    private static void deleteOld(File root) {
        File[] entries = root.listFiles();
        if (entries == null) {
            return;
        }
        long limit = System.currentTimeMillis() - OLD_COPY_MS;
        for (File entry : entries) {
            if (entry.lastModified() < limit) {
                deleteRecursively(entry);
            }
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
