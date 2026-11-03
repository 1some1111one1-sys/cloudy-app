package com.cloudy.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.cloudy.app.core.Account;
import com.cloudy.app.store.BlockCache;
import com.cloudy.app.store.CloudStore;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/** Connected drives, the cache size, importing files into a drive, and a status line. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PICK = 1;
    private static final int REQUEST_ADD = 2;

    private LinearLayout drivesBox;
    private EditText ramField, diskField;
    private TextView status;
    private String importInto;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout page = Ui.box(this);
        int pad = Ui.dp(this, 16);
        page.setPadding(pad, pad, pad, pad);

        page.addView(Ui.label(this, "Your drives"));
        drivesBox = Ui.box(this);
        page.addView(drivesBox);
        Ui.button(this, page, "Add a drive (Google Drive, MEGA, TeraBox, WebDAV)",
                v -> startActivityForResult(new Intent(this, AddDriveActivity.class), REQUEST_ADD));

        page.addView(Ui.label(this, "How much of the phone Cloudy may use for each drive"));
        android.content.SharedPreferences p = Drives.prefs(this);
        ramField = Ui.field(this, page, "RAM cache (MB)", false);
        ramField.setInputType(InputType.TYPE_CLASS_NUMBER);
        ramField.setText(String.valueOf(p.getInt("ramMb", Drives.DEFAULT_RAM_MB)));
        diskField = Ui.field(this, page, "Disk cache (MB)", false);
        diskField.setInputType(InputType.TYPE_CLASS_NUMBER);
        diskField.setText(String.valueOf(p.getInt("diskMb", Drives.DEFAULT_DISK_MB)));
        Ui.button(this, page, "Save cache size", v -> saveCacheSize());

        status = Ui.note(this, "");
        page.addView(status);

        ScrollView sv = new ScrollView(this);
        sv.addView(page);
        sv.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onStop() {
        // Push pending writes to the cloud when the app leaves the screen.
        new Thread(() -> {
            for (CloudStore s : Drives.open()) {
                try {
                    s.flush();
                } catch (Exception ignored) {
                    // retried by the 5-second auto-flush and by the next start
                }
            }
        }).start();
        super.onStop();
    }

    private void refresh() {
        drivesBox.removeAllViews();
        List<Account> accounts = AccountStore.load(this);
        if (accounts.isEmpty()) drivesBox.addView(Ui.note(this, "No drive connected yet."));
        for (Account a : accounts) {
            LinearLayout card = Ui.box(this);
            card.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            TextView title = Ui.label(this, a.name);
            card.addView(title);
            card.addView(Ui.note(this, a.summary()));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            card.addView(row);
            addRowButton(row, "Import file", v -> {
                importInto = a.id;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                startActivityForResult(i, REQUEST_PICK);
            });
            addRowButton(row, "Files", v -> showFiles(a));
            addRowButton(row, "Upload now", v -> run("Uploading...", () -> {
                Drives.store(this, a.id).flush();
                return "Everything pending was uploaded to " + a.name + ".";
            }));
            addRowButton(row, "Remove", v -> confirmRemove(a));
            drivesBox.addView(card);
        }
    }

    private void addRowButton(LinearLayout row, String text, android.view.View.OnClickListener l) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setOnClickListener(l);
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void confirmRemove(Account a) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + a.name + "?")
                .setMessage("Cloudy forgets this sign-in and its local cache. Files already stored in the cloud are not "
                        + "deleted. Anything not yet uploaded is uploaded first if the drive is reachable.")
                .setPositiveButton("Remove", (d, w) -> run("Removing...", () -> {
                    Drives.close(a.id);
                    AccountStore.remove(this, a.id);
                    Clients.reset();
                    deleteRecursive(new java.io.File(getFilesDir(), "drives/" + a.id));
                    return a.name + " was removed.";
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void deleteRecursive(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteRecursive(k);
        f.delete();
    }

    private void saveCacheSize() {
        int ram = parse(ramField.getText().toString(), Drives.DEFAULT_RAM_MB);
        int disk = parse(diskField.getText().toString(), Drives.DEFAULT_DISK_MB);
        Drives.prefs(this).edit().putInt("ramMb", ram).putInt("diskMb", disk).apply();
        run("Applying...", () -> {
            Drives.closeAll();                                  // uploads pending writes; stores reopen with the new size
            return String.format(Locale.US, "Cache size set to RAM %d MB, disk %d MB per drive.", ram, disk);
        });
    }

    private static int parse(String s, int dflt) {
        try {
            return Math.max(1, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private void showFiles(Account a) {
        run("Reading " + a.name + "...", () -> {
            CloudStore s = Drives.store(this, a.id);
            StringBuilder sb = new StringBuilder(a.name).append(":\n");
            List<CloudStore.FileInfo> files = s.list();
            if (files.isEmpty()) sb.append("  (nothing stored yet)\n");
            for (CloudStore.FileInfo f : files) {
                sb.append(String.format(Locale.US, "  %s  (%.1f MB)\n", f.path, f.size / 1048576.0));
            }
            BlockCache c = s.cache();
            BlockCache.Stats st = c.stats();
            sb.append(String.format(Locale.US, "\nOn this phone: RAM %d of %d MB, disk %d of %d MB\n",
                    st.ramBytes >> 20, c.ramCap() >> 20, st.diskBytes >> 20, c.diskCap() >> 20));
            sb.append("Cache hits ").append(st.ramHits + st.diskHits).append(", fetched from the cloud ").append(st.misses);
            return sb.toString();
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQUEST_ADD) {
            refresh();
            return;
        }
        if (req != REQUEST_PICK || res != RESULT_OK || data == null || data.getData() == null || importInto == null) return;
        final Uri uri = data.getData();
        final String accountId = importInto;
        run("Importing...", () -> {
            CloudStore s = Drives.store(this, accountId);
            String name = displayName(uri).replace('/', '_');
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return "Cannot open the selected file.";
                CloudStore.CloudFile f = s.importStream("apps/" + name, in);
                s.flush();
                return String.format(Locale.US, "Imported %s (%.1f MB) into the drive.", name, f.size() / 1048576.0);
            }
        });
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        }
        return "file-" + System.currentTimeMillis();
    }

    private interface Job {
        String run() throws Exception;
    }

    private void run(String message, Job job) {
        status.setText(message);
        new Thread(() -> {
            String result;
            try {
                result = job.run();
            } catch (Exception e) {
                result = "Error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String r = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                refresh();
                status.setText(r);
            });
        }).start();
    }
}
