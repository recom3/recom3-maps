package com.recom3.maps;

import org.osmdroid.util.TileSystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

final class Viewport {
    static final int TILE_SIZE = 256;
    static final int MIN_ZOOM = 1;
    static final int MAX_ZOOM = 19;

    int zoom = 2;
    double centerX = 0.5;
    double centerY = TileSystem.getY01FromLatitude(20, false);

    void centerOn(double latitude, double longitude) {
        centerX = wrap(TileSystem.getX01FromLongitude(longitude, false));
        centerY = clampY(TileSystem.getY01FromLatitude(latitude, false));
    }

    void zoomBy(int amount) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + amount));
    }

    void pan(double x, double y) {
        centerX = wrap(centerX + x / worldSize());
        centerY = clampY(centerY + y / worldSize());
    }

    void restore(int savedZoom, double x, double y) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, savedZoom));
        centerX = wrap(x);
        centerY = clampY(y);
    }

    float screenX(double longitude, float width) {
        double offset = TileSystem.getX01FromLongitude(longitude, false) - centerX;
        offset -= Math.floor(offset + 0.5);
        return (float) (offset * worldSize() + width / 2);
    }

    float screenY(double latitude, float height) {
        return (float) ((TileSystem.getY01FromLatitude(latitude, false) - centerY) * worldSize() + height / 2);
    }

    List<Placement> placements(float width, float height) {
        int tileCount = 1 << zoom;
        double left = centerX * worldSize() - width / 2;
        double top = centerY * worldSize() - height / 2;
        int firstX = (int) Math.floor(left / TILE_SIZE);
        int firstY = Math.max(0, (int) Math.floor(top / TILE_SIZE));
        int endX = (int) Math.ceil((left + width) / TILE_SIZE);
        int endY = Math.min(tileCount, (int) Math.ceil((top + height) / TILE_SIZE));
        List<Placement> result = new ArrayList<Placement>();
        for (int y = firstY; y < endY; y++) {
            for (int x = firstX; x < endX; x++) {
                int wrappedX = (x % tileCount + tileCount) % tileCount;
                float screenX = (float) (x * TILE_SIZE - left);
                float screenY = (float) (y * TILE_SIZE - top);
                result.add(new Placement(new Tile(zoom, wrappedX, y), screenX, screenY));
            }
        }
        return result;
    }

    List<Tile> requestOrder(List<Placement> placements, final float width, final float height) {
        List<Placement> ranked = new ArrayList<Placement>(placements);
        Collections.sort(ranked, new Comparator<Placement>() {
            @Override public int compare(Placement a, Placement b) {
                return Float.compare(distance(a), distance(b));
            }

            private float distance(Placement tile) {
                float dx = tile.x + TILE_SIZE / 2 - width / 2;
                float dy = tile.y + TILE_SIZE / 2 - height / 2;
                return dx * dx + dy * dy;
            }
        });
        LinkedHashSet<Tile> unique = new LinkedHashSet<Tile>();
        for (Placement placement : ranked) unique.add(placement.tile);
        return new ArrayList<Tile>(unique);
    }

    private int worldSize() {
        return TILE_SIZE << zoom;
    }

    private static double wrap(double x) {
        return x - Math.floor(x);
    }

    private static double clampY(double y) {
        return Math.min(1, Math.max(0, y));
    }

    static final class Placement {
        final Tile tile;
        final float x;
        final float y;

        Placement(Tile tile, float x, float y) {
            this.tile = tile;
            this.x = x;
            this.y = y;
        }
    }
}
