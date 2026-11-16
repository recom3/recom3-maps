package com.recom3.maps;

final class Tile {
    final int zoom;
    final int x;
    final int y;

    Tile(int zoom, int x, int y) {
        this.zoom = zoom;
        this.x = x;
        this.y = y;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Tile)) return false;
        Tile tile = (Tile) other;
        return zoom == tile.zoom && x == tile.x && y == tile.y;
    }

    @Override
    public int hashCode() {
        return (zoom * 31 + x) * 31 + y;
    }
}
