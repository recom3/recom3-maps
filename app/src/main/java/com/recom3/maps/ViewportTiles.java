package com.recom3.maps;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLException;

final class ViewportTiles {
    interface Listener {
        void tilesChanged();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("Recom3Tiles");
    private final Handler worker;
    private final TileTransport transport;
    private final Listener listener;
    private final Map<Tile, Bitmap> images = new HashMap<Tile, Bitmap>();
    private final Set<Tile> cacheMisses = new HashSet<Tile>();
    private List<Tile> visible = Collections.emptyList();
    private boolean connected;
    private boolean running;
    private boolean working;
    private boolean closed;
    private String error;
    private long retryAt;
    private boolean providerBackoff;
    private long viewReadyAt;
    private long generation;

    private final Runnable fetch = new Runnable() {
        @Override public void run() {
            fetchMissing();
        }
    };

    ViewportTiles(Context context, Listener listener) {
        this.listener = listener;
        transport = new TileTransport(context.getApplicationContext());
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    Bitmap image(Tile tile) {
        return images.get(tile);
    }

    boolean complete() {
        return !visible.isEmpty() && images.size() == visible.size();
    }

    String error() {
        return error;
    }

    void show(List<Tile> tiles) {
        if (visible.equals(tiles)) return;
        visible = tiles;
        images.keySet().retainAll(tiles);
        cacheMisses.retainAll(tiles);
        viewReadyAt = SystemClock.elapsedRealtime() + 180;
        schedule();
    }

    void setConnection(boolean usable) {
        if (connected == usable) return;
        connected = usable;
        generation++;
        cacheMisses.clear();
        if (usable && !providerBackoff) {
            retryAt = 0;
            error = null;
        }
        schedule();
    }

    void resume() {
        running = true;
        schedule();
    }

    void pause() {
        running = false;
        generation++;
        main.removeCallbacks(fetch);
        worker.post(new Runnable() {
            @Override public void run() {
                transport.flush();
            }
        });
    }

    void close() {
        closed = true;
        main.removeCallbacks(fetch);
        worker.post(new Runnable() {
            @Override public void run() {
                transport.flush();
                thread.quit();
            }
        });
    }

    private void schedule() {
        main.removeCallbacks(fetch);
        if (!running || closed || working || complete()) return;
        long readyAt = connected ? Math.max(retryAt, viewReadyAt) : viewReadyAt;
        main.postDelayed(fetch, Math.max(0, readyAt - SystemClock.elapsedRealtime()));
    }

    private void fetchMissing() {
        for (final Tile tile : visible) {
            if (images.containsKey(tile) || cacheMisses.contains(tile)) continue;
            final boolean online = connected;
            final long startedGeneration = generation;
            working = true;
            worker.post(new Runnable() {
                @Override public void run() {
                    load(tile, online, startedGeneration);
                }
            });
            return;
        }
    }

    private void load(final Tile tile, final boolean online, final long startedGeneration) {
        Bitmap result = null;
        String failure = null;
        long backoff = 0;
        try {
            result = transport.load(tile, online);
        } catch (TileTransport.TileHttpException e) {
            failure = e.status == 429 ? "Tile service rate limit" : "Tile service HTTP " + e.status;
            backoff = e.retryMillis;
            Log.w(TileTransport.TAG, e.getMessage());
        } catch (SSLException e) {
            failure = "TLS failed · check clock";
            Log.w(TileTransport.TAG, "Tile TLS failure", e);
        } catch (GeneralSecurityException | LinkageError e) {
            failure = "TLS unavailable";
            Log.w(TileTransport.TAG, "Tile TLS setup failure", e);
        } catch (IOException e) {
            failure = "Tile connection failed";
            Log.w(TileTransport.TAG, "Tile error: " + e.getClass().getSimpleName());
        }
        final Bitmap bitmap = result;
        final String message = failure;
        final long retry = backoff;
        main.post(new Runnable() {
            @Override public void run() {
                working = false;
                if (closed) return;
                if (retry > 0 || (online && startedGeneration == generation)) {
                    error = message;
                    providerBackoff = retry > 0;
                    retryAt = message == null ? 0 : SystemClock.elapsedRealtime() + Math.max(5000, retry);
                }
                if (visible.contains(tile)) {
                    if (bitmap != null) images.put(tile, bitmap);
                    else if (!online && startedGeneration == generation) cacheMisses.add(tile);
                }
                schedule();
                if (running) listener.tilesChanged();
            }
        });
    }
}
