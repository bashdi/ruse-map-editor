package ruse.editor.ui;

import ruse.editor.model.MapObject;
import ruse.editor.model.MapProject;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Zustand des Editors: aktuelle Karte, Werkzeug, Auswahl, Rückgängig-Verlauf und Daten aus dem Spiel. */
public final class EditorContext {

    private static final int MAX_UNDO = 60;

    private MapProject map;
    private Path file;
    private boolean dirty;

    public Tool tool = Tool.SELECT;
    /** Werkzeug Startplatz: Bündnis für neue Startplätze. */
    public int activeAlliance = 1;
    /** Werkzeug Depot: Vorrat für neue Depots. */
    public int newSupply = MapObject.DEFAULT_SUPPLY;
    /** Werkzeug Stadt-/Bergname: Name für neue Beschriftungen. */
    public String newCityName = "";
    public String newMountainName = "";
    /** Werkzeug Gebäude: Klasse für neue Gebäude. */
    public String newBuildingClass = "Building_DefenseAT";
    /** Straßennetz des Geländes. */
    public ruse.editor.model.RoadNetwork roads = ruse.editor.model.RoadNetwork.EMPTY;
    public boolean showRoads = true;

    /** Ausgewähltes Objekt oder null. */
    private MapObject selection;

    /** Übersichtskarte des Geländes (Hintergrund). */
    public BufferedImage minimap;
    /** Objekte der Originalkarte (zum Einblenden). */
    public List<MapObject> ghost = List.of();
    /** Bekannte Orts-/Bergnamen des Spiels: Token → angezeigter Text. */
    public Map<Long, String> placeNames = Map.of();
    /** Modus, dessen Startplatz-Belegung angezeigt wird (null = Bündnis/Reihenfolge anzeigen). */
    public MapProject.Mode previewMode;
    public boolean showGhost = false;
    public boolean showGrid = true;
    public boolean showLabels = true;

    private final Deque<MapProject> undo = new ArrayDeque<>();
    private final Deque<MapProject> redo = new ArrayDeque<>();
    private final List<Runnable> mapListeners = new ArrayList<>();
    private final List<Runnable> stateListeners = new ArrayList<>();

    public EditorContext(MapProject map) {
        this.map = map;
    }

    public MapProject map() { return map; }

    public Path file() { return file; }

    public boolean isDirty() { return dirty; }

    public MapObject selection() { return selection; }

    /** Neue Karte laden (Verlauf wird geleert). */
    public void setMap(MapProject m, Path file) {
        this.map = m;
        this.file = file;
        this.dirty = false;
        this.selection = null;
        undo.clear();
        redo.clear();
        if (activeAlliance > MapProject.MAX_ALLIANCES) activeAlliance = 1;
        fireMap();
    }

    public void markSaved(Path file) {
        this.file = file;
        this.dirty = false;
        fireState();
    }

    public void select(MapObject o) {
        selection = o;
        fireState();
    }

    /** Vor jeder Änderung aufrufen: legt einen Rückgängig-Punkt an. */
    public void checkpoint() {
        undo.push(map.copy());
        while (undo.size() > MAX_UNDO) undo.removeLast();
        redo.clear();
    }

    /** Nach einer Änderung aufrufen. */
    public void changed() {
        dirty = true;
        fireState();
    }

    public boolean canUndo() { return !undo.isEmpty(); }

    public boolean canRedo() { return !redo.isEmpty(); }

    public void undo() {
        if (undo.isEmpty()) return;
        redo.push(map);
        map = undo.pop();
        selection = null;
        dirty = true;
        fireMap();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        undo.push(map);
        map = redo.pop();
        selection = null;
        dirty = true;
        fireMap();
    }

    public void onMapChanged(Runnable r) { mapListeners.add(r); }

    public void onStateChanged(Runnable r) { stateListeners.add(r); }

    public void fireMap() {
        for (Runnable r : mapListeners) r.run();
        fireState();
    }

    public void fireState() {
        for (Runnable r : stateListeners) r.run();
    }

    /** Angezeigter Text eines Orts-/Bergnamens im Spiel (bekannte Namen werden übersetzt). */
    public String displayName(String name) {
        long t = ruse.editor.game.GameCatalog.token(name);
        if (t == 0) return name;
        String known = placeNames.get(t);
        return known != null ? known : name;
    }
}
