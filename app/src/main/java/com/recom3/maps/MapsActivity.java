package com.recom3.maps;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;

public final class MapsActivity extends Activity implements ViewportTiles.Listener, HudPosition.Listener {
    final Viewport viewport = new Viewport();
    private final Handler handler = new Handler();
    private HudPosition position;
    private ViewportTiles tiles;
    private MapHud hud;
    private boolean connected;
    private boolean zoomMode;
    private boolean follow = true;
    private boolean firstFix = true;
    private String link;
    private final BroadcastReceiver connectivity = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateNetwork();
        }
    };
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            positionChanged();
            handler.postDelayed(this, 1500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SharedPreferences saved = getPreferences(MODE_PRIVATE);
        if (saved.contains("zoom")) {
            viewport.restore(saved.getInt("zoom", 2),
                    Double.longBitsToDouble(saved.getLong("x", Double.doubleToLongBits(0.5))),
                    Double.longBitsToDouble(saved.getLong("y", Double.doubleToLongBits(0.5))));
            follow = saved.getBoolean("follow", true);
            firstFix = saved.getBoolean("firstFix", true);
        }
        position = new HudPosition(this, this);
        tiles = new ViewportTiles(this, this);
        hud = new MapHud(this, tiles, position);
        setContentView(hud);
        if (BuildConfig.DEBUG) Log.i(TileTransport.TAG, "Recom3 Maps started");
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(connectivity, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        updateNetwork();
        tiles.resume();
        position.start();
        handler.post(clock);
    }

    @Override protected void onPause() {
        unregisterReceiver(connectivity);
        handler.removeCallbacks(clock);
        position.stop();
        tiles.pause();
        getPreferences(MODE_PRIVATE).edit().putInt("zoom", viewport.zoom)
                .putLong("x", Double.doubleToLongBits(viewport.centerX))
                .putLong("y", Double.doubleToLongBits(viewport.centerY))
                .putBoolean("follow", follow).putBoolean("firstFix", firstFix).apply();
        super.onPause();
    }

    @Override protected void onDestroy() {
        tiles.close();
        super.onDestroy();
    }

    private void updateNetwork() {
        ConnectivityManager service = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo network = service.getActiveNetworkInfo();
        boolean online = network != null && network.isConnected();
        String label = "No network";
        if (online) {
            int type = network.getType();
            if (type == ConnectivityManager.TYPE_WIFI) label = "Wi-Fi";
            else if (type == ConnectivityManager.TYPE_BLUETOOTH) label = "Bluetooth PAN";
            else label = "Network";
        }
        if (BuildConfig.DEBUG && !label.equals(link)) Log.i(TileTransport.TAG, "Network route: " + label);
        connected = online;
        link = label;
        tiles.setConnection(online);
        hud.invalidate();
    }

    String networkLabel() {
        if (!connected) return "Offline";
        if (tiles.complete()) return link;
        if (tiles.error() != null) return link + " · error";
        return link + " · loading";
    }

    String tileMessage() {
        if (!connected) return tiles.complete() ? "Cached tiles" : "No network · cached tiles only";
        return tiles.complete() ? null : tiles.error();
    }

    boolean followsGps() {
        return follow;
    }

    boolean isZoomMode() {
        return zoomMode;
    }

    @Override public void tilesChanged() {
        hud.invalidate();
    }

    @Override public void positionChanged() {
        Location fix = position.fix();
        if (follow && fix != null) {
            if (firstFix) {
                viewport.zoom = Math.max(14, viewport.zoom);
                firstFix = false;
            }
            viewport.centerOn(fix.getLatitude(), fix.getLongitude());
        }
        hud.updateMap();
    }

    void pan(float x, float y) {
        follow = false;
        firstFix = false;
        viewport.pan(x, y);
        hud.updateMap();
    }

    void zoom(int amount) {
        firstFix = false;
        viewport.zoomBy(amount);
        hud.updateMap();
        if (BuildConfig.DEBUG) Log.i(TileTransport.TAG, "Zoom level " + viewport.zoom);
    }

    void recenter() {
        follow = true;
        zoomMode = false;
        if (position.fix() == null) Toast.makeText(this, position.gpsLabel(), Toast.LENGTH_SHORT).show();
        positionChanged();
        if (BuildConfig.DEBUG) Log.i(TileTransport.TAG, "GPS follow enabled");
    }

    void openCredits() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.TILE_CREDIT_URL)));
        } catch (ActivityNotFoundException noBrowser) {
            Toast.makeText(this, BuildConfig.TILE_CREDIT_URL, Toast.LENGTH_LONG).show();
        }
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                boolean forward = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (zoomMode) {
                    if (event.getRepeatCount() == 0) zoom(forward ? 1 : -1);
                } else {
                    float x = code == KeyEvent.KEYCODE_DPAD_LEFT ? -75 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 75 : 0;
                    float y = code == KeyEvent.KEYCODE_DPAD_UP ? -64 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 64 : 0;
                    pan(x, y);
                    if (BuildConfig.DEBUG && event.getRepeatCount() == 0) {
                        Log.i(TileTransport.TAG, "Pan key=" + code + " scan=" + event.getScanCode());
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (event.getRepeatCount() == 0) {
                    zoomMode = !zoomMode;
                    hud.invalidate();
                    if (BuildConfig.DEBUG) Log.i(TileTransport.TAG, (zoomMode ? "Mode zoom" : "Mode pan") + " scan=" + event.getScanCode());
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_PLUS:
                if (event.getRepeatCount() == 0) zoom(1);
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_MINUS:
                if (event.getRepeatCount() == 0) zoom(-1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_1:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_MOVE_HOME:
                if (event.getRepeatCount() == 0) recenter();
                return true;
            default:
                return super.onKeyDown(code, event);
        }
    }

    @Override public void onBackPressed() {
        if (!zoomMode && !follow) {
            recenter();
        } else if (zoomMode) {
            zoomMode = false;
            hud.invalidate();
        } else {
            super.onBackPressed();
        }
    }
}
