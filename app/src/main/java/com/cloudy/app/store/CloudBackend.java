package com.cloudy.app.store;

import java.io.IOException;

/**
 * Minimal object-store interface. Anything that can GET / PUT / DELETE a named blob
 * can be a backend: WebDAV (Nextcloud, Synology, ownCloud...), a folder synced by another
 * app, S3-compatible storage, or a Drive/OneDrive REST adapter.
 *
 * Keys are flat names such as "catalog" or "b-<fileId>-<blockIndex>" (no slashes), so no
 * directory creation is ever needed on the remote side.
 */
public interface CloudBackend {
    /** @return the object's bytes, or null if it does not exist. */
    byte[] get(String key) throws IOException;

    void put(String key, byte[] data) throws IOException;

    /** Deleting a missing object is not an error. */
    void delete(String key) throws IOException;
}
