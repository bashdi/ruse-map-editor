package ruse.editor.io;

import ruse.editor.i18n.I18n;
import ruse.editor.model.MapObject;
import ruse.editor.model.MapProject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lesen und Schreiben des Kartenformats (.rusemap, JSON).
 * Version 2: Koordinaten in Spieleinheiten. Version 1 (mit gemaltem Gelände) wird beim Laden umgerechnet.
 */
public final class MapIO {

    public static final String EXTENSION = ".rusemap";
    private static final int FORMAT_VERSION = 2;
    /** Spieleinheiten je Rasterzelle in Version 1. */
    private static final double V1_UNITS_PER_CELL = 5120;

    private MapIO() {}

    public static void save(MapProject m, Path file) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "ruse-map-editor");
        root.put("version", FORMAT_VERSION);
        root.put("name", m.name);
        root.put("author", m.author);
        root.put("description", m.description);
        root.put("modified", Instant.now().toString());
        root.put("gameBase", m.gameBase);
        root.put("terrain", m.terrain);
        root.put("datapack", m.datapack);
        root.put("extentX", m.extentX);
        root.put("extentY", m.extentY);
        root.put("players", m.players);
        List<Object> modes = new ArrayList<>();
        for (MapProject.Mode md : m.modes) modes.add(md.name());
        root.put("modes", modes);
        root.put("mainMode", m.mainMode.name());

        List<Object> objs = new ArrayList<>();
        for (MapObject ob : m.objects) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("kind", ob.kind.name());
            o.put("x", Math.round(ob.x));
            o.put("y", Math.round(ob.y));
            switch (ob.kind) {
                case START -> {
                    o.put("alliance", ob.alliance);
                    o.put("priority", ob.priority);
                    o.put("rotation", round(ob.rotation));
                }
                case DEPOT -> {
                    o.put("supply", ob.supply);
                    o.put("rotation", round(ob.rotation));
                }
                case CITY, MOUNTAIN -> o.put("name", ob.name);
                case BUILDING -> {
                    o.put("building", ob.buildingClass);
                    o.put("rotation", round(ob.rotation));
                }
            }
            objs.add(o);
        }
        root.put("objects", objs);

        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @SuppressWarnings("unchecked")
    public static MapProject load(Path file) throws IOException {
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
            throw new IOException(I18n.tr("err.not_a_map_detail", e.getMessage()), e);
        }
        if (!(parsed instanceof Map)) throw new IOException(I18n.tr("err.not_a_map"));
        Map<String, Object> root = (Map<String, Object>) parsed;
        if (!"ruse-map-editor".equals(root.get("format"))) throw new IOException(I18n.tr("err.unknown_format"));
        int version = num(root, "version", 1);

        MapProject m = new MapProject();
        m.name = str(root, "name", "Karte");
        m.author = str(root, "author", "");
        m.description = str(root, "description", "");
        m.gameBase = str(root, "gameBase", null);
        m.players = Math.max(MapProject.MIN_PLAYERS, Math.min(MapProject.MAX_PLAYERS, num(root, "players", 2)));
        List<Object> objs = (List<Object>) root.getOrDefault("objects", List.of());

        if (version >= 2) {
            m.terrain = str(root, "terrain", "");
            m.datapack = str(root, "datapack", "");
            m.extentX = dbl(root, "extentX", 0);
            m.extentY = dbl(root, "extentY", 0);
            if (root.get("modes") instanceof List<?> l) {
                m.modes.clear();
                for (Object o : l) {
                    try {
                        m.modes.add(MapProject.Mode.valueOf((String) o));
                    } catch (RuntimeException ignored) {
                        // unbekannter Modus
                    }
                }
            }
            try {
                m.mainMode = MapProject.Mode.valueOf(str(root, "mainMode", "TEAMS2"));
            } catch (IllegalArgumentException ignored) {
                m.mainMode = MapProject.Mode.TEAMS2;
            }
            for (Object oo : objs) {
                Map<String, Object> o = (Map<String, Object>) oo;
                MapObject.Kind kind;
                try {
                    kind = MapObject.Kind.valueOf((String) o.get("kind"));
                } catch (RuntimeException e) {
                    continue;
                }
                MapObject ob = new MapObject(kind, dbl(o, "x", 0), dbl(o, "y", 0));
                ob.alliance = num(o, "alliance", 1);
                ob.priority = num(o, "priority", 1);
                ob.supply = num(o, "supply", MapObject.DEFAULT_SUPPLY);
                ob.rotation = dbl(o, "rotation", 0);
                ob.name = str(o, "name", "");
                ob.buildingClass = str(o, "building", ob.buildingClass);
                m.objects.add(ob);
            }
            return m;
        }

        // ---- Version 1: Rasterzellen; Startplätze je Spieler, Depots mit Besitzer, Brücken/Straßen/Gelände
        int w = num(root, "width", 256), h = num(root, "height", 256);
        m.extentX = w * V1_UNITS_PER_CELL;
        m.extentY = h * V1_UNITS_PER_CELL;
        if (m.gameBase == null) m.extentX = m.extentY = 0; // Gelände muss beim Öffnen gewählt werden
        m.description = (m.description + "").trim();
        legacyCells.put(m, new int[]{w, h});
        m.modes.clear();
        m.modes.add(MapProject.Mode.TEAMS2);
        if (m.players >= 3) m.modes.add(MapProject.Mode.FFA);
        m.mainMode = MapProject.Mode.TEAMS2;
        for (Object oo : objs) {
            Map<String, Object> o = (Map<String, Object>) oo;
            String kind = (String) o.get("kind");
            double x = dbl(o, "x", 0) * V1_UNITS_PER_CELL, y = dbl(o, "y", 0) * V1_UNITS_PER_CELL;
            MapObject ob;
            switch (kind == null ? "" : kind) {
                case "START" -> {
                    ob = new MapObject(MapObject.Kind.START, x, y);
                    // alter Startplatz je Spieler → Bündnis = Spieler (Jeder gegen jeden) …
                    ob.alliance = num(o, "player", 1);
                }
                case "DEPOT" -> ob = new MapObject(MapObject.Kind.DEPOT, x, y);
                case "CITY" -> {
                    ob = new MapObject(MapObject.Kind.CITY, x, y);
                    ob.name = str(o, "name", "");
                }
                default -> {
                    continue; // Brücken und gemaltes Gelände kann das Spiel nicht übernehmen
                }
            }
            m.objects.add(ob);
        }
        // … und zusätzlich reihum auf Bündnis 1 und 2 für Teamspiele
        List<MapObject> starts = new ArrayList<>(m.objects.stream().filter(o -> o.kind == MapObject.Kind.START).toList());
        starts.sort(Comparator.comparingInt(o -> o.alliance));
        for (MapObject s : starts) {
            int p = s.alliance;
            if (p > 2) {
                MapObject team = s.copy();
                team.alliance = (p - 1) % 2 + 1;
                m.objects.add(team);
            }
        }
        for (int a = 1; a <= MapProject.MAX_ALLIANCES; a++) m.renumber(a);
        return m;
    }

    /** Rastergröße von Karten im alten Format (für die Umrechnung, wenn erst beim Öffnen ein Gelände gewählt wird). */
    private static final Map<MapProject, int[]> legacyCells = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Rechnet eine alte Karte ohne Gelände auf die Ausdehnung des gewählten Geländes um. */
    public static void fitLegacy(MapProject m, double extentX, double extentY) {
        int[] cells = legacyCells.remove(m);
        double sx = cells == null ? 1 : extentX / (cells[0] * V1_UNITS_PER_CELL);
        double sy = cells == null ? 1 : extentY / (cells[1] * V1_UNITS_PER_CELL);
        for (MapObject o : m.objects) {
            o.x *= sx;
            o.y *= sy;
        }
        m.extentX = extentX;
        m.extentY = extentY;
    }

    /** Kopfdaten für die Kartenbibliothek. */
    public static String[] readHeader(Path file) {
        try {
            MapProject m = load(file);
            return new String[]{m.name, m.terrain, String.valueOf(m.players), m.author};
        } catch (Exception e) {
            return new String[]{file.getFileName().toString(), "?", "?", "Fehler: " + e.getMessage()};
        }
    }

    private static double round(double d) { return Math.round(d * 10) / 10.0; }

    private static int num(Map<String, Object> m, String k, int def) {
        return m.get(k) instanceof Number n ? n.intValue() : def;
    }

    private static double dbl(Map<String, Object> m, String k, double def) {
        return m.get(k) instanceof Number n ? n.doubleValue() : def;
    }

    private static String str(Map<String, Object> m, String k, String def) {
        return m.get(k) instanceof String s ? s : def;
    }
}
