package ruse.editor.model;

import java.util.List;

/** Straßennetz eines Geländes (Strecken in Spieleinheiten) mit Hilfen zum Ausrichten von Gebäuden. */
public final class RoadNetwork {

    /**
     * Abstand eines Depots von der Straßenmitte (Median der Originalkarten: 11 850 Einheiten ≈ 120 m).
     * Die Drehung ist dort stets Richtung-zur-Straße + 90°, also parallel zur Straße.
     */
    public static final double DEPOT_OFFSET = 11850;
    /** Bis zu diesem Abstand rastet ein Depot an der Straße ein (≈ 900 m). */
    public static final double SNAP_DISTANCE = 90000;

    public static final RoadNetwork EMPTY = new RoadNetwork(List.of());

    private final List<float[]> segments;

    public RoadNetwork(List<float[]> segments) {
        this.segments = segments;
    }

    public List<float[]> segments() { return segments; }

    public boolean isEmpty() { return segments.isEmpty(); }

    /** Nächster Punkt auf einer Straße: Abstand, Fußpunkt und Straßenrichtung (Einheitsvektor). */
    public record Hit(double distance, double px, double py, double dirX, double dirY) {}

    public Hit nearest(double x, double y) {
        Hit best = null;
        for (float[] s : segments) {
            double dx = s[2] - s[0], dy = s[3] - s[1], l2 = dx * dx + dy * dy;
            if (l2 < 1e-6) continue;
            double t = Math.max(0, Math.min(1, ((x - s[0]) * dx + (y - s[1]) * dy) / l2));
            double px = s[0] + t * dx, py = s[1] + t * dy, d = Math.hypot(x - px, y - py);
            if (best == null || d < best.distance()) {
                double l = Math.sqrt(l2);
                best = new Hit(d, px, py, dx / l, dy / l);
            }
        }
        return best;
    }

    /**
     * Setzt ein Depot wie in den Originalkarten neben die nächste Straße: im festen Abstand auf der Seite,
     * auf die geklickt wurde, parallel zur Straße ausgerichtet. Liefert false, wenn keine Straße nah genug ist.
     */
    public boolean placeBesideRoad(MapObject o, double x, double y) {
        Hit h = nearest(x, y);
        if (h == null || h.distance() > SNAP_DISTANCE) return false;
        // Normale zur Straße, auf die Seite des Klicks
        double nx = -h.dirY(), ny = h.dirX();
        if ((x - h.px()) * nx + (y - h.py()) * ny < 0) {
            nx = -nx;
            ny = -ny;
        }
        o.x = h.px() + nx * DEPOT_OFFSET;
        o.y = h.py() + ny * DEPOT_OFFSET;
        o.rotation = Math.toDegrees(Math.atan2(-ny, -nx)) + 90;
        o.rotation = ((o.rotation + 180) % 360 + 360) % 360 - 180;
        return true;
    }

    /** Richtet ein Objekt parallel zur nächsten Straße aus, ohne es zu verschieben. */
    public boolean alignToRoad(MapObject o) {
        Hit h = nearest(o.x, o.y);
        if (h == null || h.distance() > SNAP_DISTANCE) return false;
        double toRoad = Math.toDegrees(Math.atan2(h.py() - o.y, h.px() - o.x));
        o.rotation = ((toRoad + 90 + 180) % 360 + 360) % 360 - 180;
        return true;
    }
}
