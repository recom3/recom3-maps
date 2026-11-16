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
    private boolean directNetwork;
    private boolean companionCheckPending;
    private TileTransport.Route route = TileTransport.Route.OFFLINE;
    private ReconWebBridge.State companion = ReconWebBridge.State.CHECKING;
    private long nextCompanionCheck;
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
        if (directNetwork && !usable) {
            companion = ReconWebBridge.State.CHECKING;
            nextCompanionCheck = 0;
        }
        directNetwork = usable;
        updateRoute();
        refreshCompanion();
    }

    TileTransport.Route route() {
        return route;
    }

    String companionMessage() {
        if (companion == ReconWebBridge.State.UNAVAILABLE) return "Recon SDK web unavailable";
        if (companion == ReconWebBridge.State.CHECKING) return "Checking Recon SDK";
        return "Waiting for phone web";
    }

    void refreshCompanion() {
        long now = SystemClock.elapsedRealtime();
        if (directNetwork || closed || !running || companionCheckPending || now < nextCompanionCheck) return;
        companionCheckPending = true;
        nextCompanionCheck = now + 3000;
        worker.post(new Runnable() {
            @Override public void run() {
                final ReconWebBridge.State state = transport.probeCompanion();
                main.post(new Runnable() {
                    @Override public void run() {
                        companionCheckPending = false;
                        if (closed) return;
                        companion = state;
                        updateRoute();
                        if (running) listener.tilesChanged();
                    }
                });
            }
        });
    }

    private void updateRoute() {
        TileTransport.Route next = TileTransport.Route.OFFLINE;
        if (directNetwork) next = TileTransport.Route.DIRECT;
        else if (companion == ReconWebBridge.State.READY) next = TileTransport.Route.SDK;
        if (next == route) return;
        route = next;
        generation++;
        cacheMisses.clear();
        if (route != TileTransport.Route.OFFLINE && !providerBackoff) {
            retryAt = 0;
            error = null;
        }
        if (BuildConfig.DEBUG) Log.i(TileTransport.TAG, "Tile route: " + route);
        schedule();
    }

    void resume() {
        running = true;
        refreshCompanion();
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
                transport.close();
                thread.quit();
            }
        });
    }

    private void schedule() {
        main.removeCallbacks(fetch);
        if (!running || closed || working || complete()) return;
        long readyAt = route == TileTransport.Route.OFFLINE ? viewReadyAt : Math.max(retryAt, viewReadyAt);
        main.postDelayed(fetch, Math.max(0, readyAt - SystemClock.elapsedRealtime()));
    }

    private void fetchMissing() {
        for (final Tile tile : visible) {
            if (images.containsKey(tile) || cacheMisses.contains(tile)) continue;
            final TileTransport.Route selected = route;
            final long startedGeneration = generation;
            working = true;
            worker.post(new Runnable() {
                @Override public void run() {
                    load(tile, selected, startedGeneration);
                }
            });
            return;
        }
    }

    private void load(final Tile tile, final TileTransport.Route selected, final long startedGeneration) {
        final boolean online = selected != TileTransport.Route.OFFLINE;
        Bitmap result = null;
        String failure = null;
        long backoff = 0;
        try {
            result = transport.load(tile, selected);
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
            failure = selected == TileTransport.Route.SDK ? "Phone tile request failed" : "Tile connection failed";
            if (selected == TileTransport.Route.SDK) {
                Log.w(TileTransport.TAG, "Tile Recon SDK error: " + e.getMessage());
            } else {
                Log.w(TileTransport.TAG, "Tile error: " + e.getClass().getSimpleName());
            }
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
