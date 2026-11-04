package com.clouddroid.app.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/** URL form-encoding helper shared by the MEGA and TeraBox clients. */
final class Urls {
    private Urls() {
    }

    static String enc(String v) {
        try {
            return URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
