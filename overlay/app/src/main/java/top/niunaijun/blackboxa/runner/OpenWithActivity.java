package top.niunaijun.blackboxa.runner;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
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
        String pkg;
        ComponentName component; // a screen that declares it can open the file, or null
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
            Log.d(TAG, "asked to open " + data + " type=" + original.getType());
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
            List<ResolveInfo> matches = null;
            try {
                matches = BlackBoxCore.getBPackageManager().queryIntentActivities(
                        probe, PackageManager.GET_META_DATA, type, USER_ID);
            } catch (Throwable t) {
                Log.w(TAG, "could not check which apps support the file", t); // list everything
            }
            // package name -> a screen that says it can open this kind of file
            java.util.Map<String, ActivityInfo> supporting = new java.util.LinkedHashMap<>();
            if (matches != null) {
                for (ResolveInfo match : matches) {
                    if (match.activityInfo != null
                            && !supporting.containsKey(match.activityInfo.packageName)) {
                        supporting.put(match.activityInfo.packageName, match.activityInfo);
                    }
                }
            }

            // Every app in the container is an option, whether or not it declares support.
            PackageManager pm = getPackageManager();
            List<android.content.pm.PackageInfo> installed =
                    BlackBoxCore.get().getInstalledPackages(0, USER_ID);
            final List<Choice> supportsIt = new ArrayList<>();
            final List<Choice> others = new ArrayList<>();
            if (installed != null) {
                for (android.content.pm.PackageInfo info : installed) {
                    Choice choice = new Choice();
                    choice.pkg = info.packageName;
                    choice.label = info.packageName;
                    try {
                        if (info.applicationInfo != null) {
                            choice.label = String.valueOf(info.applicationInfo.loadLabel(pm));
                            choice.icon = info.applicationInfo.loadIcon(pm);
                        }
                    } catch (Throwable ignored) {
                        // keep what we have
                    }
                    ActivityInfo screen = supporting.get(info.packageName);
                    if (screen != null) {
                        choice.component = new ComponentName(screen.packageName, screen.name);
                        supportsIt.add(choice);
                    } else {
                        others.add(choice);
                    }
                }
            }
            final List<Choice> choices = new ArrayList<>(supportsIt);
            choices.addAll(others);
            ui.post(() -> show(choices));
        } catch (Throwable t) {
            Log.w(TAG, "could not look for apps", t);
            ui.post(() -> fail("Could not look for apps in the container: " + t.getMessage()));
        }
    }

    // ---------------------------------------------------------------- Choosing

    private void show(List<Choice> choices) {
        if (choices.isEmpty()) {
            fail("There are no apps in the container yet. Add one in BG App Runner first.");
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
                row.setPadding(dp(22), dp(12), dp(20), dp(12));
                ImageView icon = new ImageView(OpenWithActivity.this);
                if (choice.icon != null) {
                    icon.setImageDrawable(choice.icon);
                }
                row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout texts = new LinearLayout(OpenWithActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(16), 0, 0, 0);
                TextView name = new TextView(OpenWithActivity.this);
                name.setText(choice.label);
                name.setTextSize(16);
                name.setTextColor(Glass.TEXT);
                name.setTypeface(Glass.medium());
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                texts.addView(name);
                if (choice.component == null) {
                    TextView hint = new TextView(OpenWithActivity.this);
                    hint.setText("Not known to support this file. It will be sent anyway.");
                    hint.setTextSize(12);
                    hint.setTextColor(Glass.TEXT3);
                    hint.setPadding(0, dp(2), 0, 0);
                    texts.addView(hint);
                }
                row.addView(texts, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return row;
            }
        };

        // A frosted glass sheet over the dimmed screen, with the apps listed inside it.
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(GlassPanel.solid(dp(26), dp(1)));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            panel.setClipToOutline(true);
        }

        TextView tag = new TextView(this);
        tag.setText("Open with");
        tag.setTextSize(10);
        tag.setTextColor(Glass.CYAN);
        tag.setTypeface(Glass.mono());
        tag.setAllCaps(true);
        tag.setLetterSpacing(0.18f);
        tag.setPadding(dp(24), dp(22), dp(24), 0);
        panel.addView(tag);

        TextView title = new TextView(this);
        title.setText("Choose an app in the container");
        title.setTextSize(20);
        title.setTextColor(Glass.TEXT);
        title.setTypeface(Glass.medium());
        title.setPadding(dp(24), dp(4), dp(24), dp(12));
        panel.addView(title);

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(new ColorDrawable(0x1AFFFFFF));
        list.setDividerHeight(Math.max(1, dp(1)));
        list.setSelector(Glass.rowRipple());
        list.setVerticalScrollBarEnabled(false);
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            open(choices.get(position));
        });
        // Room for six apps at a time; more scroll.
        panel.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(choices.size(), 6) * dp(65)));
        View bottom = new View(this);
        panel.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnCancelListener(d -> finish());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(dp(380), getResources().getDisplayMetrics().widthPixels - dp(40));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(0.62f);
        }
        dialog.show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- Opening

    private void open(Choice choice) {
        Toast.makeText(this, "Opening in " + choice.label + "...", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                ComponentName target = choice.component;
                if (target == null) {
                    // The app does not declare support: hand the file to its main screen anyway.
                    Intent main = new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(choice.pkg);
                    ResolveInfo launcher = BlackBoxCore.getBPackageManager()
                            .resolveActivity(main, 0, null, USER_ID);
                    if (launcher == null || launcher.activityInfo == null) {
                        ui.post(() -> fail(choice.label + " has no screen to open."));
                        return;
                    }
                    target = new ComponentName(launcher.activityInfo.packageName,
                            launcher.activityInfo.name);
                }
                Uri copy = copyToShared(original.getData());
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(copy, type != null ? type : getContentResolver().getType(copy));
                intent.setComponent(target);
                // The engine's launch screen needs the package name set, or it closes without opening.
                intent.setPackage(target.getPackageName());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                // Apps look for the file in the intent's data, or in its clip, depending on how
                // they were written. Offer it in both places, like a file manager does.
                intent.setClipData(ClipData.newRawUri(copy.getLastPathSegment(), copy));
                intent.putExtra("bg_runner_from_host", true);
                Log.d(TAG, "opening " + copy + " in " + intent.getComponent().flattenToShortString());
                ui.post(() -> {
                    try {
                        BlackBoxCore.get().startActivity(intent, USER_ID);
                    } catch (Throwable t) {
                        Log.w(TAG, "could not start the app", t);
                        Toast.makeText(this, "Could not open the app: " + t.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                    // Stay on screen a moment so Android still sees this app in front.
                    ui.postDelayed(this::finish, 1500);
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
