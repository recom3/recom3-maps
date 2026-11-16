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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

final class TileTransport {
    static final String TAG = "Recom3Maps";
    static final String PUBLIC_HOST = "tile.openstreetmap.org";
    static final int MAX_TILE_BYTES = 512 * 1024;
    private static final long CACHE_BYTES = 12L * 1024 * 1024;
    private static final long REQUEST_INTERVAL_MS = 350;
    private static final String USER_AGENT = "Recom3Maps/" + BuildConfig.VERSION_NAME + " (hud@recom3.com)";
    private static final String IMAGE_TYPES = "image/png, image/jpeg";

    enum Route { DIRECT, SDK, OFFLINE }

    private final Context context;
    private final ReconWebBridge bridge;
    private final SdkTileCache sdkCache;
    private HttpResponseCache disk;
    private SSLContext standardTls;
    private SSLContext osmTls;
    private long nextRequest;

    TileTransport(Context context) {
        this.context = context;
        bridge = new ReconWebBridge(context);
        sdkCache = new SdkTileCache(new File(context.getCacheDir(), "tile-sdk"));
    }

    ReconWebBridge.State probeCompanion() {
        return bridge.probe();
    }

    void close() {
        bridge.close();
        flush();
    }

    Bitmap load(Tile tile, Route route) throws IOException, GeneralSecurityException {
        URL url = new URL(BuildConfig.TILE_URL.replace("{z}", Integer.toString(tile.zoom))
                .replace("{x}", Integer.toString(tile.x)).replace("{y}", Integer.toString(tile.y)));
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) {
            throw new SSLException("Tile endpoint must use HTTPS with no embedded credentials");
        }

        if (route == Route.SDK) return loadSdkTile(url, tile, true);
        Bitmap direct = loadDirectTile(url, tile, route == Route.DIRECT);
        if (direct != null || route == Route.DIRECT) return direct;
        return loadSdkTile(url, tile, false);
    }

    private Bitmap loadDirectTile(URL url, Tile tile, boolean connected) throws IOException, GeneralSecurityException {
        installCache();
        HttpsURLConnection request = (HttpsURLConnection) url.openConnection();
        request.setSSLSocketFactory(tlsFor(url).getSocketFactory());
        request.setInstanceFollowRedirects(false);
        request.setConnectTimeout(5000);
        request.setReadTimeout(7000);
        request.setUseCaches(true);
        request.setRequestProperty("User-Agent", USER_AGENT);
        request.setRequestProperty("Accept", IMAGE_TYPES);
        if (!connected) request.setRequestProperty("Cache-Control", "only-if-cached");

        try {
            int hitsBefore = disk.getHitCount();
            int networkBefore = disk.getNetworkCount();
            if (connected) throttle();
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
            Bitmap tileImage = decodeTile(readTile(request));
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

    private Bitmap loadSdkTile(URL url, Tile tile, boolean connected) throws IOException {
        SdkTileCache.Entry cached = sdkCache.read(url);
        if (cached != null && cached.fresh()) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Tile Recon SDK cache z=" + tile.zoom);
            return decodeTile(cached.body);
        }
        if (!connected) return null;

        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        headers.put("User-Agent", Collections.singletonList(USER_AGENT));
        headers.put("Accept", Collections.singletonList(IMAGE_TYPES));
        if (cached != null) {
            if (!cached.etag.isEmpty()) headers.put("If-None-Match", Collections.singletonList(cached.etag));
            if (!cached.modified.isEmpty()) {
                headers.put("If-Modified-Since", Collections.singletonList(cached.modified));
            }
        }
        throttle();
        ReconWebBridge.Response response = bridge.get(url, headers);
        if (response.status == 304 && cached != null) {
            sdkCache.store(url, response.headers, cached.body, cached);
            if (BuildConfig.DEBUG) Log.i(TAG, "Tile Recon SDK revalidated z=" + tile.zoom + " HTTP 304");
            return decodeTile(cached.body);
        }
        if (response.status != 200) {
            String retry = SdkTileCache.header(response.headers, "Retry-After");
            long wait = Math.max(SdkTileCache.seconds(retry),
                    SdkTileCache.httpDate(retry) - System.currentTimeMillis());
            throw new TileHttpException(response.status, Math.max(60000, wait));
        }
        requireImageType(SdkTileCache.header(response.headers, "Content-Type"));
        Bitmap image = decodeTile(response.body);
        sdkCache.store(url, response.headers, response.body, null);
        if (BuildConfig.DEBUG) Log.i(TAG, "Tile Recon SDK z=" + tile.zoom + " HTTP 200");
        return image;
    }

    private void throttle() {
        SystemClock.sleep(Math.max(0, nextRequest - SystemClock.elapsedRealtime()));
        nextRequest = SystemClock.elapsedRealtime() + REQUEST_INTERVAL_MS;
    }

    private static Bitmap decodeTile(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > MAX_TILE_BYTES) {
            throw new IOException("Invalid tile length");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth != 256 || options.outHeight != 256) {
            throw new IOException("Expected a 256×256 raster tile");
        }
        options.inJustDecodeBounds = false;
        Bitmap tileImage = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (tileImage == null) throw new IOException("Tile could not be decoded");
        return tileImage;
    }

    private static void requireImageType(String type) throws IOException {
        if (type == null || !(type.startsWith("image/png") || type.startsWith("image/jpeg"))) {
            throw new IOException("Tile response is not PNG or JPEG");
        }
    }

    private byte[] readTile(HttpsURLConnection request) throws IOException {
        requireImageType(request.getContentType());
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
