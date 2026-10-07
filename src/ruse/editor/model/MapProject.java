package ruse.editor.model;

import ruse.editor.i18n.I18n;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Eine eigene Mehrspielerkarte: Gelände einer Originalkarte plus alles, was R.U.S.E. aus dem Szenario
 * übernimmt (Startplätze, Nachschubdepots, Stadt- und Bergnamen) und die Einstellungen der Kartenauswahl.
 */
public final class MapProject {

    public static final int MIN_PLAYERS = 2, MAX_PLAYERS = 8, MAX_ALLIANCES = 8;

    /** Spielmodi, für die eine Karte in der Auswahl freigegeben werden kann. */
    public enum Mode {
        FFA(1, 0),
        TEAMS2(2, 2),
        TEAMS3(3, 3),
        TEAMS4(4, 4);

        /** Wert von GameModeMulti im Spiel. */
        public final int gameValue;
        /** Anzahl Teams (0 = jeder für sich). */
        public final int teams;
        public final String label;

        Mode(int gameValue, int teams) {
            this.gameValue = gameValue;
            this.teams = teams;
            this.label = I18n.tr("mode." + name());
        }

        /** Ist der Modus mit dieser Spielerzahl überhaupt möglich? */
        public boolean possibleWith(int players) {
            if (teams == 0) return players >= 3;
            return players % teams == 0 && players >= (teams == 2 ? 2 : 2 * teams);
        }
    }

    public String name = I18n.tr("map.default_name");
    public String author = "";
    public String description = "";
    /** Eintrag der Kartenauswahl, dessen Gelände die Karte nutzt, z. B. "(2) Face a face". */
    public String gameBase;
    /** Geländename (Anzeige), z. B. "Chess". */
    public String terrain = "";
    /** Datenpaket des Geländes (Maps\PC\DataMap&lt;datapack&gt;_v09.dat). */
    public String datapack = "";
    /** Ausdehnung des Geländes in Spieleinheiten. */
    public double extentX, extentY;
    public int players = 2;
    public final Set<Mode> modes = EnumSet.of(Mode.TEAMS2);
    public Mode mainMode = Mode.TEAMS2;
    public final List<MapObject> objects = new ArrayList<>();

    public boolean hasTerrain() {
        return gameBase != null && extentX > 0 && extentY > 0;
    }

    public boolean inside(double x, double y) {
        return x >= 0 && y >= 0 && x <= extentX && y <= extentY;
    }

    public MapProject copy() {
        MapProject m = new MapProject();
        m.name = name;
        m.author = author;
        m.description = description;
        m.gameBase = gameBase;
        m.terrain = terrain;
        m.datapack = datapack;
        m.extentX = extentX;
        m.extentY = extentY;
        m.players = players;
        m.modes.clear();
        m.modes.addAll(modes);
        m.mainMode = mainMode;
        for (MapObject o : objects) m.objects.add(o.copy());
        return m;
    }

    // ------------------------------------------------------------------ Startplätze

    /** Startplätze eines Bündnisses in Belegungsreihenfolge. */
    public List<MapObject> startsOf(int alliance) {
        List<MapObject> l = new ArrayList<>();
        for (MapObject o : objects) if (o.kind == MapObject.Kind.START && o.alliance == alliance) l.add(o);
        l.sort(Comparator.comparingInt(o -> o.priority));
        return l;
    }

    public int nextPriority(int alliance) {
        int max = 0;
        for (MapObject o : startsOf(alliance)) max = Math.max(max, o.priority);
        return max + 1;
    }

    /** Ordnet die Reihenfolgen eines Bündnisses lückenlos 1, 2, 3 … */
    public void renumber(int alliance) {
        int i = 1;
        for (MapObject o : startsOf(alliance)) o.priority = i++;
    }

    /**
     * Welcher Startplatz wird in einem Modus von Spieler {@code player} (1..players) belegt?
     * Teams: Spieler werden reihum auf die Teams verteilt; Team t nutzt die Startplätze von Bündnis t
     * in ihrer Reihenfolge. Jeder gegen jeden: Spieler p nutzt den ersten Startplatz von Bündnis p.
     */
    public MapObject startFor(Mode mode, int player) {
        if (mode.teams == 0) {
            List<MapObject> s = startsOf(player);
            return s.isEmpty() ? null : s.get(0);
        }
        int team = (player - 1) % mode.teams + 1;
        int slot = (player - 1) / mode.teams;
        List<MapObject> s = startsOf(team);
        return slot < s.size() ? s.get(slot) : null;
    }

    /** Team bzw. Spielerfarbe eines Spielers in einem Modus (für die Anzeige). */
    public static int teamOf(Mode mode, int player) {
        return mode.teams == 0 ? player : (player - 1) % mode.teams + 1;
    }

    // ------------------------------------------------------------------ Prüfung

    public record Issue(boolean error, String text) {}

    public List<Issue> validate() {
        List<Issue> out = new ArrayList<>();
        if (!hasTerrain()) out.add(new Issue(true, I18n.tr("check.no_terrain")));
        if (modes.isEmpty()) out.add(new Issue(true, I18n.tr("check.no_mode")));
        if (!modes.contains(mainMode)) out.add(new Issue(true, I18n.tr("check.main_mode_disabled")));
        for (Mode m : modes) {
            if (!m.possibleWith(players)) {
                out.add(new Issue(true, I18n.tr("check.mode_impossible", m.label, players)));
                continue;
            }
            List<Integer> missing = new ArrayList<>();
            for (int p = 1; p <= players; p++) if (startFor(m, p) == null) missing.add(p);
            if (!missing.isEmpty()) {
                out.add(new Issue(true, m.teams == 0
                        ? I18n.tr("check.missing_starts_ffa", m.label, join(missing), players)
                        : I18n.tr("check.missing_starts_teams", m.label, join(missing), m.teams, players / m.teams)));
            }
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MapObject o : objects) {
            if (!inside(o.x, o.y)) out.add(new Issue(true, I18n.tr("check.outside", o.describe())));
            if (o.kind == MapObject.Kind.START && !seen.add(o.alliance + "/" + o.priority)) {
                out.add(new Issue(true, I18n.tr("check.duplicate_start", o.alliance, o.priority)));
            }
            if (o.kind.isLabel() && o.name.isBlank()) out.add(new Issue(true, I18n.tr("check.label_without_name", o.kind.label)));
        }
        long depots = objects.stream().filter(o -> o.kind == MapObject.Kind.DEPOT).count();
        if (depots == 0) out.add(new Issue(false, I18n.tr("check.no_depots")));
        else if (depots < players) out.add(new Issue(false, I18n.tr("check.few_depots", depots, players)));
        return out;
    }

    private static String join(List<Integer> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) sb.append(i == 0 ? "" : ", ").append(l.get(i));
        return sb.toString();
    }
}
