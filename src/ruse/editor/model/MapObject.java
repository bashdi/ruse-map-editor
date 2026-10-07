package ruse.editor.model;

import ruse.editor.i18n.I18n;

/**
 * Ein Objekt, das R.U.S.E. aus dem Szenario einer Mehrspielerkarte übernimmt.
 * Koordinaten in Spieleinheiten (x nach rechts, y nach unten wie die Übersichtskarte).
 */
public final class MapObject {

    public enum Kind {
        START, DEPOT, CITY, MOUNTAIN, BUILDING;

        public final String label;

        Kind() { this.label = I18n.tr("kind." + name()); }

        public boolean isLabel() { return this == CITY || this == MOUNTAIN; }
    }

    public static final int MIN_SUPPLY = 5, MAX_SUPPLY = 100, DEFAULT_SUPPLY = 25;

    public Kind kind;
    public double x, y;
    /** Startplatz: Bündnis (1..8). */
    public int alliance = 1;
    /** Startplatz: Reihenfolge innerhalb des Bündnisses (1 = zuerst belegt). */
    public int priority = 1;
    /** Depot: Vorrat (Anzahl Nachschub-Lkw). */
    public int supply = DEFAULT_SUPPLY;
    /** Ausrichtung in Grad (Depot, Startplatz). */
    public double rotation;
    /** Stadt- oder Bergname. */
    public String name = "";
    /** Gebäude: Klassenname, z. B. "Building_DefenseAT". */
    public String buildingClass = "Building_DefenseAT";

    public MapObject(Kind kind, double x, double y) {
        this.kind = kind;
        this.x = x;
        this.y = y;
    }

    public MapObject copy() {
        MapObject o = new MapObject(kind, x, y);
        o.alliance = alliance;
        o.priority = priority;
        o.supply = supply;
        o.rotation = rotation;
        o.name = name;
        o.buildingClass = buildingClass;
        return o;
    }

    public String describe() {
        return switch (kind) {
            case START -> I18n.tr("describe.start", alliance, priority);
            case DEPOT -> I18n.tr("describe.depot", supply);
            case CITY -> I18n.tr("describe.city", name);
            case MOUNTAIN -> I18n.tr("describe.mountain", name);
            case BUILDING -> Buildings.of(buildingClass).toString();
        };
    }
}
