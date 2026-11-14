package top.niunaijun.blackbox.core.env;

import android.util.Log;

import java.io.File;

/**
 * Makes sure the "phone storage" of the container has the folders every app expects.
 *
 * On a real phone Android creates {@code Android/data/<package>/files}, {@code Android/obb/<package>},
 * {@code Download}, {@code DCIM} and so on. Inside the container nothing did: the container keeps its
 * own copy of the storage tree under this app's folder, and the engine's StorageManager.mkdirs does
 * nothing. A game that then wrote its first file into its own data folder got "no such directory",
 * did not check for that and crashed (GTA San Andreas, right after loading, in CColAccel::endCache).
 *
 * Everything here runs before the file redirect is switched on, so the paths are real ones.
 */
public final class ExternalFolders {
    private static final String[] COMMON = {
            "Android/data", "Android/obb", "Android/media",
            "Download", "Documents", "DCIM", "Pictures", "Movies", "Music",
            "Alarms", "Notifications", "Ringtones", "Podcasts"
    };

    private ExternalFolders() {
    }

    /**
     * @param root        the container's copy of the storage root (".../blackbox/storage/emulated/N")
     * @param packageName the app that is about to run
     */
    public static void prepare(File root, String packageName) {
        if (root == null) {
            return;
        }
        try {
            make(root);
            for (String name : COMMON) {
                make(new File(root, name));
            }
            if (packageName != null && !packageName.isEmpty()) {
                make(new File(root, "Android/data/" + packageName + "/files"));
                make(new File(root, "Android/data/" + packageName + "/cache"));
                make(new File(root, "Android/obb/" + packageName));
                make(new File(root, "Android/media/" + packageName));
                File files = new File(root, "Android/data/" + packageName + "/files");
                Log.i("ExternalFolders", "folders for " + packageName + " ready: " + files
                        + " exists=" + files.isDirectory());
            }
        } catch (Throwable ignored) {
            // The app then behaves as it did before; nothing here may stop it from starting.
        }
    }

    private static void make(File dir) {
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
    }
}
