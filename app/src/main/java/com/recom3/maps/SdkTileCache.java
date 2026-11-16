package com.recom3.maps;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

final class SdkTileCache {
    private static final int FILE_VERSION = 1;
    private static final long LIMIT = 12L * 1024 * 1024;
    private static final long ONE_WEEK_MS = 7L * 86400000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File directory;

    SdkTileCache(File directory) {
        this.directory = directory;
    }

    Entry read(URL url) {
        File file = fileFor(url);
        if (!file.isFile()) return null;
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            try {
                if (in.readInt() != FILE_VERSION) throw new IOException("Tile cache version");
                long expires = in.readLong();
                long lifetime = in.readLong();
                String etag = in.readUTF();
                String modified = in.readUTF();
                int length = in.readInt();
                if (length < 1 || length > TileTransport.MAX_TILE_BYTES) throw new IOException("Tile cache length");
                byte[] body = new byte[length];
                in.readFully(body);
                file.setLastModified(System.currentTimeMillis());
                return new Entry(expires, lifetime, etag, modified, body);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            file.delete();
            return null;
        }
    }

    void store(URL url, Map<String, List<String>> headers, byte[] body, Entry previous) {
        String cacheControl = header(headers, "Cache-Control");
        String vary = header(headers, "Vary");
        boolean revalidate = false;
        long maxAge = -1;
        for (String piece : cacheControl.split(",")) {
            String directive = piece.trim().toLowerCase(Locale.US);
            if ("no-store".equals(directive) || "*".equals(vary.trim())) {
                fileFor(url).delete();
                return;
            }
            if (directive.equals("no-cache") || directive.startsWith("no-cache=")) revalidate = true;
            if (directive.startsWith("max-age=")) maxAge = seconds(directive.substring(8));
        }
        long now = System.currentTimeMillis();
        long date = httpDate(header(headers, "Date"));
        long expires = httpDate(header(headers, "Expires"));
        long lifetime = ONE_WEEK_MS;
        if (revalidate) lifetime = 0;
        else if (maxAge >= 0) lifetime = maxAge;
        else if (expires > 0) lifetime = Math.max(0, expires - (date > 0 ? date : now));
        else if (previous != null) lifetime = previous.lifetime;
        long age = Math.max(0, seconds(header(headers, "Age")));
        if (date > 0) age = Math.max(age, now - date);
        String etag = header(headers, "ETag");
        String modified = header(headers, "Last-Modified");
        Entry entry = new Entry(now + Math.max(0, lifetime - age), lifetime,
                etag.isEmpty() && previous != null ? previous.etag : etag,
                modified.isEmpty() && previous != null ? previous.modified : modified, body);
        write(url, entry);
    }

    private void write(URL url, Entry entry) {
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        File temporary = null;
        try {
            temporary = File.createTempFile("tile-", ".tmp", directory);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)));
            try {
                out.writeInt(FILE_VERSION);
                out.writeLong(entry.expires);
                out.writeLong(entry.lifetime);
                out.writeUTF(entry.etag);
                out.writeUTF(entry.modified);
                out.writeInt(entry.body.length);
                out.write(entry.body);
            } finally {
                out.close();
            }
            File target = fileFor(url);
            boolean saved = temporary.renameTo(target);
            if (!saved && target.isFile() && target.delete()) {
                saved = temporary.renameTo(target);
            }
            if (saved) trim();
        } catch (IOException ignored) {
            // A full or inaccessible disk does not prevent showing a downloaded tile.
        } finally {
            if (temporary != null) temporary.delete();
        }
    }

    private void trim() {
        File[] tiles = directory.listFiles();
        if (tiles == null) return;
        long bytes = 0;
        for (File tile : tiles) bytes += tile.length();
        if (bytes <= LIMIT) return;
        Arrays.sort(tiles, new Comparator<File>() {
            @Override public int compare(File first, File second) {
                long difference = first.lastModified() - second.lastModified();
                return difference < 0 ? -1 : difference > 0 ? 1 : 0;
            }
        });
        for (File tile : tiles) {
            if (bytes <= LIMIT) break;
            long size = tile.length();
            if (tile.delete()) bytes -= size;
        }
    }

    private File fileFor(URL url) {
        String key = UUID.nameUUIDFromBytes(url.toExternalForm().getBytes(UTF8)).toString();
        return new File(directory, key);
    }

    static long seconds(String value) {
        try {
            long parsed = Long.parseLong(value.trim().replace("\"", ""));
            return parsed < 0 ? -1 : Math.min(parsed, Integer.MAX_VALUE) * 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static long httpDate(String value) {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        format.setLenient(false);
        try {
            return format.parse(value).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    static String header(Map<String, List<String>> headers, String name) {
        if (headers == null) return "";
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            List<String> values = header.getValue();
            if (!name.equalsIgnoreCase(header.getKey()) || values == null || values.isEmpty()) continue;
            StringBuilder joined = new StringBuilder();
            for (String value : values) {
                if (value == null) continue;
                if (joined.length() > 0) joined.append(", ");
                joined.append(value);
            }
            return joined.toString();
        }
        return "";
    }

    static final class Entry {
        final long expires;
        final long lifetime;
        final String etag;
        final String modified;
        final byte[] body;

        Entry(long expires, long lifetime, String etag, String modified, byte[] body) {
            this.expires = expires;
            this.lifetime = lifetime;
            this.etag = etag;
            this.modified = modified;
            this.body = body;
        }

        boolean fresh() {
            return expires > System.currentTimeMillis();
        }
    }
}
