// SPDX-License-Identifier: MIT
package io.github.ruderthanyou.housetools;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Small blocking HTTP and JSON helpers. Call only from background threads. */
final class Net {
    private Net() {}

    static byte[] get(String url, int maxBytes) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(20_000);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for " + shortUrl(url));
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    if (out.size() > maxBytes) throw new IOException("Too large: " + shortUrl(url));
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Decode, scaled down by powers of two until it is no more than twice the target size. RGB_565 saves memory. */
    static Bitmap decode(byte[] data, int targetWidth, int targetHeight, boolean alpha) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    static String shortUrl(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    /** Percent-encode one path segment (file name). */
    static String encodeSegment(String segment) {
        StringBuilder out = new StringBuilder();
        byte[] bytes = segment.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xff;
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
            if (plain) out.append((char) c);
            else out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
        }
        return out.toString();
    }

    /** JSON text or an already-parsed value from the host, as plain Java maps and lists. */
    static Object plain(Object value) throws JSONException {
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.startsWith("{")) return plain(new JSONObject(text));
            if (text.startsWith("[")) return plain(new JSONArray(text));
            return value;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Map<String, Object> map = new LinkedHashMap<>();
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String key = keys.next();
                Object item = object.get(key);
                map.put(key, item == JSONObject.NULL ? null : (item instanceof JSONObject || item instanceof JSONArray ? plain(item) : item));
            }
            return map;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                Object item = array.get(i);
                list.add(item == JSONObject.NULL ? null : (item instanceof JSONObject || item instanceof JSONArray ? plain(item) : item));
            }
            return list;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    static String str(Object value) { return value == null ? "" : String.valueOf(value); }
}
