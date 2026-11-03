package com.cloudy.app;

import com.cloudy.app.store.CloudStore;

/**
 * The seam where an app-virtualization engine would plug in. NOT IMPLEMENTED in this version.
 *
 * What the storage layer already gives an engine: every file of a "virtual install" (base.apk, split APKs, OBB,
 * data files) is a {@link CloudStore.CloudFile} with random-access read/write, answered from a size-capped RAM + disk
 * cache and otherwise streamed from Google Drive, MEGA, TeraBox or WebDAV.
 *
 * What an engine must still do (the hard part):
 *  - load the guest app's code from those files (DexClassLoader needs real file paths, so dex has to be
 *    materialised in the cache or served through a proxy file descriptor);
 *  - run it with its own package identity, in this process or a stub process;
 *  - redirect the guest's file access (getFilesDir(), obb, external storage) to store paths such as
 *    "data/&lt;pkg&gt;/...". Native code that calls open()/mmap() directly bypasses a Java-level redirect and needs
 *    system-call interception, as VirtualApp-style containers do;
 *  - cope with apps that detect or refuse to run in a container.
 */
public interface ContainerEngine {
    /** Register a guest app whose files already live under {@code root} in the store, e.g. "apps/demo". */
    void install(CloudStore store, String root) throws Exception;

    /** Start the guest app. */
    void launch(String packageName) throws Exception;

    /** Remove the guest app's runtime state (its cloud files are removed through the store). */
    void uninstall(String packageName) throws Exception;
}
