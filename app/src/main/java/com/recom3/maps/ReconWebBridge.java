package com.recom3.maps;

import android.content.Context;
import android.util.Log;

import com.reconinstruments.os.HUDOS;
import com.reconinstruments.os.connectivity.HUDConnectivityManager;
import com.reconinstruments.os.connectivity.IHUDConnectivity;
import com.reconinstruments.os.connectivity.http.HUDHttpRequest;
import com.reconinstruments.os.connectivity.http.HUDHttpResponse;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Uses the firmware's web service, which owns the Android SPP and iPhone MFi transports. */
final class ReconWebBridge {
    enum State { CHECKING, UNAVAILABLE, WAITING, READY }

    static final class Response {
        final int status;
        final Map<String, List<String>> headers;
        final byte[] body;

        Response(int status, Map<String, List<String>> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    private final Context context;
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>());
    private HUDConnectivityManager manager;
    private boolean loadedAshmem;
    private boolean reportedUnavailable;

    ReconWebBridge(Context context) {
        this.context = context;
    }

    State probe() {
        try {
            if (manager == null) {
                String[] libraries = context.getPackageManager().getSystemSharedLibraryNames();
                if (libraries != null && Arrays.asList(libraries).contains("com.reconinstruments.os")) {
                    Object service = HUDOS.getHUDService(HUDOS.HUD_CONNECTIVITY_SERVICE);
                    if (service instanceof HUDConnectivityManager) manager = (HUDConnectivityManager) service;
                }
            }
            if (manager == null) return unavailable("connectivity manager not exposed");
            if (!loadedAshmem) {
                // The original SDK uses this system JNI library for the binary response body.
                System.loadLibrary("reconinstruments_jni");
                loadedAshmem = true;
            }
            // Some companion builds serve HTTP without answering the SDK's web-status query.
            boolean connected = manager.getConnectionState() == IHUDConnectivity.ConnectionState.CONNECTED;
            return (manager.hasWebConnection() || connected) ? State.READY : State.WAITING;
        } catch (RuntimeException | LinkageError missing) {
            manager = null;
            return unavailable(missing.getClass().getSimpleName());
        }
    }

    private State unavailable(String reason) {
        if (!reportedUnavailable) {
            reportedUnavailable = true;
            Log.i(TileTransport.TAG, "Recon SDK web service unavailable: " + reason);
        }
        return State.UNAVAILABLE;
    }

    Response get(final URL url, final Map<String, List<String>> headers) throws IOException {
        final HUDConnectivityManager current = manager;
        if (current == null) throw new IOException("Recon SDK web service unavailable");
        Future<Response> request;
        try {
            request = requests.submit(new Callable<Response>() {
                @Override public Response call() throws Exception {
                    HUDHttpRequest query = new HUDHttpRequest(HUDHttpRequest.RequestMethod.GET, url, headers);
                    query.setTimeout(10000);
                    HUDHttpResponse response = current.sendWebRequest(query);
                    if (response == null || response.getResponseCode() < 100) {
                        throw new IOException("Recon SDK web request failed");
                    }
                    return new Response(response.getResponseCode(), response.getHeaders(), response.getBody());
                }
            });
        } catch (RejectedExecutionException busy) {
            // Older firmware can hold a pending Binder call after its HTTP timeout.
            throw new IOException("Waiting for the previous Recon SDK request", busy);
        }
        try {
            return request.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            request.cancel(true);
            throw new IOException("Recon SDK request timed out", e);
        } catch (InterruptedException e) {
            request.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Recon SDK request interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Recon SDK request failed", e.getCause());
        }
    }

    void close() {
        requests.shutdownNow();
    }
}
