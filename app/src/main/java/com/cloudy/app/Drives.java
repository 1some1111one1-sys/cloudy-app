package com.cloudy.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.cloudy.app.core.Account;
import com.cloudy.app.store.CloudStore;
import com.cloudy.app.store.DriveBackend;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One block store per connected drive. Files are kept in the cloud as 4 MB blocks; only a RAM + disk cache of the
 * size set on the main screen is kept on the phone.
 */
final class Drives {
    /** The folder, inside each drive, that holds everything Cloudy stores there. */
    static final String ROOT_FOLDER = "/CloudyDrive";
    static final int BLOCK_SIZE = 4 * 1024 * 1024;
    static final int DEFAULT_RAM_MB = 128;
    static final int DEFAULT_DISK_MB = 1024;

    private static final Map<String, CloudStore> STORES = new HashMap<>();

    private Drives() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("cloudy_settings", Context.MODE_PRIVATE);
    }

    /** Opens (or returns) the store of a drive. May talk to the network the first time, so not for the main thread. */
    static synchronized CloudStore store(Context ctx, String accountId) throws IOException {
        CloudStore s = STORES.get(accountId);
        if (s != null) return s;
        Context c = ctx.getApplicationContext();
        Account a = AccountStore.find(c, accountId);
        if (a == null) throw new IOException("That drive is no longer connected.");
        SharedPreferences p = prefs(c);
        long ram = p.getInt("ramMb", DEFAULT_RAM_MB) * 1024L * 1024L;
        long disk = p.getInt("diskMb", DEFAULT_DISK_MB) * 1024L * 1024L;
        // filesDir, not cacheDir: Android may wipe cacheDir when storage is low, which would destroy writes that have
        // not been uploaded yet. The size is still capped by the disk budget above.
        File dir = new File(c.getFilesDir(), "drives/" + accountId);
        DriveBackend backend = new DriveBackend(Clients.get(a), ROOT_FOLDER, new File(dir, "tmp"));
        s = new CloudStore(backend, new File(dir, "cache"), ram, disk, BLOCK_SIZE, 3);
        s.startAutoFlush(5_000);
        STORES.put(accountId, s);
        return s;
    }

    /** Uploads everything pending and forgets the store (settings changed, or the drive was removed). */
    static synchronized void close(String accountId) {
        CloudStore s = STORES.remove(accountId);
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // pending writes stay on disk and are retried the next time the drive is opened
            }
        }
    }

    static synchronized void closeAll() {
        for (String id : new ArrayList<>(STORES.keySet())) close(id);
    }

    static synchronized List<CloudStore> open() {
        return new ArrayList<>(STORES.values());
    }
}
