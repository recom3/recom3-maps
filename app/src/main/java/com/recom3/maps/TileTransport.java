package com.recom3.maps;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.http.HttpResponseCache;
import android.os.SystemClock;
import android.util.Log;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

final class TileTransport {
    static final String TAG = "Recom3Maps";
    static final String PUBLIC_HOST = "tile.openstreetmap.org";
    private static final long CACHE_BYTES = 24L * 1024 * 1024;
    private static final int MAX_TILE_BYTES = 512 * 1024;
    private static final long REQUEST_INTERVAL_MS = 350;

    private final Context context;
    private HttpResponseCache disk;
    private SSLContext standardTls;
    private SSLContext osmTls;
    private long nextRequest;

    TileTransport(Context context) {
        this.context = context;
    }

    Bitmap load(Tile tile, boolean connected) throws IOException, GeneralSecurityException {
        installCache();
        URL url = new URL(BuildConfig.TILE_URL.replace("{z}", Integer.toString(tile.zoom))
                .replace("{x}", Integer.toString(tile.x)).replace("{y}", Integer.toString(tile.y)));
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) {
            throw new SSLException("Tile endpoint must use HTTPS with no embedded credentials");
        }

        HttpsURLConnection request = (HttpsURLConnection) url.openConnection();
        request.setSSLSocketFactory(tlsFor(url).getSocketFactory());
        request.setInstanceFollowRedirects(false);
        request.setConnectTimeout(5000);
        request.setReadTimeout(7000);
        request.setUseCaches(true);
        request.setRequestProperty("User-Agent", "Recom3Maps/" + BuildConfig.VERSION_NAME + " (hud@recom3.com)");
        request.setRequestProperty("Accept", "image/png, image/jpeg");
        if (!connected) request.setRequestProperty("Cache-Control", "only-if-cached");

        try {
            int hitsBefore = disk.getHitCount();
            int networkBefore = disk.getNetworkCount();
            if (connected) {
                SystemClock.sleep(Math.max(0, nextRequest - SystemClock.elapsedRealtime()));
                nextRequest = SystemClock.elapsedRealtime() + REQUEST_INTERVAL_MS;
            }
            int status = request.getResponseCode();
            if (status != 200) {
                // A cache-only miss is HTTP 502 on Jelly Bean and 504 on newer builds.
                if (!connected) return null;
                long retrySeconds = request.getHeaderFieldInt("Retry-After", 60);
                long retryDate = request.getHeaderFieldDate("Retry-After", -1);
                long wait = Math.max(Math.max(60, retrySeconds) * 1000L,
                        retryDate - System.currentTimeMillis());
                throw new TileHttpException(status, wait);
            }
            byte[] data = readTile(request);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (options.outWidth != 256 || options.outHeight != 256) {
                throw new IOException("Expected a 256×256 raster tile");
            }
            options.inJustDecodeBounds = false;
            Bitmap tileImage = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (tileImage == null) throw new IOException("Tile could not be decoded");
            boolean cacheHit = disk.getHitCount() > hitsBefore;
            boolean network = disk.getNetworkCount() > networkBefore;
            if (BuildConfig.DEBUG) {
                String source = cacheHit ? (network ? "HTTP cache revalidated" : "HTTP cache") : "HTTPS";
                Log.i(TAG, "Tile " + source + " z=" + tile.zoom + " HTTP " + status + " "
                        + (cacheHit ? "" : request.getCipherSuite()));
            }
            return tileImage;
        } finally {
            request.disconnect();
        }
    }

    private byte[] readTile(HttpsURLConnection request) throws IOException {
        String type = request.getContentType();
        if (type == null || !(type.startsWith("image/png") || type.startsWith("image/jpeg"))) {
            throw new IOException("Tile response is not PNG or JPEG");
        }
        if (request.getContentLength() > MAX_TILE_BYTES) throw new IOException("Tile is too large");
        InputStream stream = request.getInputStream();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (bytes.size() + read > MAX_TILE_BYTES) throw new IOException("Tile is too large");
                bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        } finally {
            stream.close();
        }
    }

    void flush() {
        if (disk != null) disk.flush();
    }

    private void installCache() throws IOException {
        if (disk != null) return;
        File directory = new File(context.getCacheDir(), "tile-http");
        disk = HttpResponseCache.install(directory, CACHE_BYTES);
    }

    private SSLContext tlsFor(URL url) throws GeneralSecurityException, IOException {
        // Fastly's current OSM chain reaches R46, which is absent from Jelly Bean.
        // Scope this trust anchor to the default tile host.
        if (PUBLIC_HOST.equalsIgnoreCase(url.getHost())) {
            if (osmTls == null) {
                Certificate root;
                InputStream cert = context.getResources().openRawResource(R.raw.globalsign_r46);
                try {
                    root = CertificateFactory.getInstance("X.509").generateCertificate(cert);
                } finally {
                    cert.close();
                }
                KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
                trust.load(null, null);
                trust.setCertificateEntry("globalsign-r46", root);
                TrustManagerFactory managers = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                managers.init(trust);
                osmTls = SSLContext.getInstance("TLSv1.2", Conscrypt.newProvider());
                osmTls.init(null, managers.getTrustManagers(), null);
            }
            return osmTls;
        }
        if (standardTls == null) {
            standardTls = SSLContext.getInstance("TLSv1.2", Conscrypt.newProvider());
            standardTls.init(null, null, null);
        }
        return standardTls;
    }

    static final class TileHttpException extends IOException {
        final int status;
        final long retryMillis;

        TileHttpException(int status, long retryMillis) {
            super("Tile HTTP " + status);
            this.status = status;
            this.retryMillis = retryMillis;
        }
    }
}
