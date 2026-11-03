package com.cloudy.app;

import com.cloudy.app.core.Account;
import com.cloudy.app.core.CloudClient;
import com.cloudy.app.core.CloudClients;
import com.cloudy.app.core.HttpTransport;
import java.util.HashMap;
import java.util.Map;

/** One shared client per account, so a MEGA or TeraBox sign-in is reused instead of repeated. */
final class Clients {
    static final HttpTransport TRANSPORT = new OkHttpTransport();
    private static final Map<String, CloudClient> CACHE = new HashMap<>();

    private Clients() {
    }

    static synchronized CloudClient get(Account a) {
        String key = a.id + "|" + a.secret + "|" + a.url + "|" + a.clientSecret;
        CloudClient c = CACHE.get(key);
        if (c == null) {
            c = CloudClients.create(a, TRANSPORT);
            CACHE.put(key, c);
        }
        return c;
    }

    /** Call after accounts were added, edited or removed. */
    static synchronized void reset() {
        CACHE.clear();
    }
}
