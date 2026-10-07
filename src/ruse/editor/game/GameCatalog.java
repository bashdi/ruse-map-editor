package ruse.editor.game;

import ruse.editor.i18n.I18n;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Liest aus den (Original-)Archiven die Kartenliste und alles, was zu einer Basiskarte gehört:
 * Szenario, Cluster-Definitionen, Kamerapfade und Geländeausdehnung.
 */
public final class GameCatalog {

    public static final String MAPINFO_PATCHABLE = "genglad\\patchable\\mapinfo.cpp.gladndfbin";
    public static final String MAPINFO_NOTPATCHABLE = "genglad\\nonpatchable\\mapinfo.cpp.gladndfbin";

    /** Ein Eintrag der Kartenauswahl, der als Grundlage dienen kann. */
    public record BaseMap(String name, String terrain, String rootDatapack, String clusterBaseName, int players,
                          String iconPath, byte[] guid) {
        @Override public String toString() { return name; }

        /** Ordnername des Geländes in den Pfaden, z. B. "Chess". */
        public String terrainFolder() { return clusterBaseName.split("\\\\")[2]; }
    }

    /** Alle Daten einer Basiskarte, die für eine neue Karte gebraucht werden. */
    public static final class BaseScenario {
        public BaseMap map;
        public float extentX, extentY;
        public byte[] scenarioFile;
        public int ndfStart;
        public Ndf scenario;
        public String scenarioDataDirPath;
        public Ndf clusterMap;
        public Ndf mapIa;
        public String mapIaBaseName;
        public String campathDataDirPath;
        public Ndf campath;
    }

    private final EdatArchive patchable;
    private final EdatArchive dataMap;
    private final Path zzWinPath;
    private EdatArchive zzWin;
    private final Ndf mapinfo;
    private Ndf globals;
    private final List<BaseMap> maps = new ArrayList<>();

    public GameCatalog(Path patchableArchive, Path dataMapArchive) throws IOException {
        this(patchableArchive, dataMapArchive, null);
    }

    public GameCatalog(Path patchableArchive, Path dataMapArchive, Path zzWinArchive) throws IOException {
        zzWinPath = zzWinArchive;
        patchable = new EdatArchive(patchableArchive);
        dataMap = new EdatArchive(dataMapArchive);
        mapinfo = Ndf.read(read(patchable, MAPINFO_PATCHABLE));
        int cls = mapinfo.classIndex("TMapLoadInfo");
        for (Ndf.Obj o : mapinfo.objects) {
            if (o.classIndex != cls) continue;
            String name = mapinfo.getString(o, "Name");
            Ndf.Value mask = mapinfo.get(o, "MapTypeMask");
            if (name == null || mask == null || mask.asInt() != 16 || !name.startsWith("(")) continue;
            String terrain = mapinfo.getString(o, "Path");
            String root = mapinfo.getString(o, "RootDatapackName");
            String base = clusterBaseName(mapinfo, o);
            if (terrain == null || base == null) continue;
            int players = 0;
            int close = name.indexOf(')');
            try {
                players = Integer.parseInt(name.substring(1, close).trim());
            } catch (RuntimeException ignored) {
                // Spielerzahl unbekannt
            }
            Ndf.Obj icon = mapinfo.deref(mapinfo.get(o, "Icone"));
            Ndf.Value guid = mapinfo.get(o, "GUID");
            maps.add(new BaseMap(name, terrain, root == null ? terrain : root, base, players,
                    icon == null ? null : mapinfo.getString(icon, "FileName"), guid == null ? null : guid.raw));
        }
    }

    // ------------------------------------------------------------------ Einstellungen der Kartenauswahl

    /** Einstellungen eines Eintrags der Gefechtsliste (TMultiMapInfo). */
    public record MultiInfo(int players, java.util.Set<ruse.editor.model.MapProject.Mode> modes,
                            ruse.editor.model.MapProject.Mode mainMode) {}

    public MultiInfo multiInfo(BaseMap map) throws IOException {
        if (globals == null) globals = Ndf.read(read(patchable, MapInstaller.GLOBALS));
        int cls = globals.classIndex("TMultiMapInfo");
        for (Ndf.Obj o : globals.objects) {
            Ndf.Value g = o.classIndex == cls ? globals.get(o, "GUID") : null;
            if (g == null || map.guid() == null || !java.util.Arrays.equals(g.raw, map.guid())) continue;
            java.util.Set<ruse.editor.model.MapProject.Mode> modes =
                    java.util.EnumSet.noneOf(ruse.editor.model.MapProject.Mode.class);
            if (flag(o, "DispoMultiFFA")) modes.add(ruse.editor.model.MapProject.Mode.FFA);
            if (flag(o, "DispoMulti2Teams")) modes.add(ruse.editor.model.MapProject.Mode.TEAMS2);
            if (flag(o, "DispoMulti3Teams")) modes.add(ruse.editor.model.MapProject.Mode.TEAMS3);
            if (flag(o, "DispoMulti4Teams")) modes.add(ruse.editor.model.MapProject.Mode.TEAMS4);
            Ndf.Value gm = globals.get(o, "GameModeMulti"), nb = globals.get(o, "NbPlayers");
            ruse.editor.model.MapProject.Mode main = ruse.editor.model.MapProject.Mode.TEAMS2;
            for (ruse.editor.model.MapProject.Mode m : ruse.editor.model.MapProject.Mode.values()) {
                if (gm != null && intOf(gm) == m.gameValue) main = m;
            }
            return new MultiInfo(nb == null ? map.players() : intOf(nb), modes, main);
        }
        return new MultiInfo(map.players(), java.util.EnumSet.of(ruse.editor.model.MapProject.Mode.TEAMS2),
                ruse.editor.model.MapProject.Mode.TEAMS2);
    }

    private boolean flag(Ndf.Obj o, String prop) {
        Ndf.Value v = globals.get(o, prop);
        return v != null && intOf(v) != 0;
    }

    static int intOf(Ndf.Value v) {
        if (v.raw == null) return v.index;
        ByteBuffer b = ByteBuffer.wrap(v.raw).order(ByteOrder.LITTLE_ENDIAN);
        return switch (v.raw.length) {
            case 1 -> v.raw[0];
            case 2 -> b.getShort(0);
            default -> b.getInt(0);
        };
    }

    // ------------------------------------------------------------------ Straßennetz

    /**
     * Straßennetz eines Geländes aus {@code datasmap\<gelände>\mapinfo.win} (Signatur "INFOIA"): bei 0x34 Anzahl
     * Knoten und Kanten (je u16), danach Offsets (relativ zu 0x34) auf die Knoten (u32 Kennung, f32 x, f32 y)
     * und die Kanten (u16 von, u16 nach, u16 Länge/10). Liefert Strecken als {x1, y1, x2, y2}.
     */
    public List<float[]> roads(BaseMap map) throws IOException {
        byte[] d = read(dataMap, "datasmap\\" + map.terrain().toLowerCase() + "\\mapinfo.win");
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        float w = b.getFloat(0x28), h = b.getFloat(0x2c);
        int nodes = b.getShort(0x34) & 0xffff, edges = b.getShort(0x36) & 0xffff;
        int nodeOff = 0x34 + b.getInt(0x38), edgeOff = 0x34 + b.getInt(0x3c);
        if (nodeOff + nodes * 12L > d.length || edgeOff + edges * 6L > d.length) throw new IOException(I18n.tr("err.roads"));
        float[] nx = new float[nodes], ny = new float[nodes];
        for (int i = 0; i < nodes; i++) {
            nx[i] = b.getFloat(nodeOff + 12 * i + 4);
            ny[i] = b.getFloat(nodeOff + 12 * i + 8);
        }
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < edges; i++) {
            int a = b.getShort(edgeOff + 6 * i) & 0xffff, c = b.getShort(edgeOff + 6 * i + 2) & 0xffff;
            if (a >= nodes || c >= nodes) continue;
            if (!inside(nx[a], ny[a], w, h) || !inside(nx[c], ny[c], w, h)) continue;
            out.add(new float[]{nx[a], ny[a], nx[c], ny[c]});
        }
        return out;
    }

    private static boolean inside(float x, float y, float w, float h) {
        return x >= 0 && y >= 0 && x <= w && y <= h;
    }

    // ------------------------------------------------------------------ Bilder und Namen aus ZZ_Win.dat

    private EdatArchive zzWin() throws IOException {
        if (zzWin == null) {
            if (zzWinPath == null) throw new IOException(I18n.tr("err.zzwin_missing"));
            zzWin = new EdatArchive(zzWinPath);
        }
        return zzWin;
    }

    /** Vorschaubild der Kartenauswahl (Szenenbild, 640×360). */
    public java.awt.image.BufferedImage preview(BaseMap map) throws IOException {
        if (map.iconPath() == null) throw new IOException(I18n.tr("err.no_preview"));
        String p = "gen\\" + archivePath(map.iconPath()).replaceAll("\\.png$", ".tgv");
        return TgvImage.decode(read(zzWin(), p));
    }

    /**
     * Bekannte Orts- und Bergnamen des Spiels (Token → angezeigter Text) in der Sprache {@code lang}
     * ("ger", "us", "fr", …). Das Token entspricht den ersten 10 Zeichen des Schlüssels.
     */
    public java.util.Map<Long, String> placeNames(String lang) throws IOException {
        String suffix = "\\translations\\" + lang + "\\ville_multi.dic";
        for (EdatArchive.Entry e : zzWin().entries()) {
            if (e.path().endsWith(suffix)) return LocDictionary.entries(zzWin().read(e));
        }
        throw new IOException(I18n.tr("err.place_names", lang));
    }

    /** Schlüsseltext eines Tokens (höchstens 10 Zeichen), z. B. für "Dover". */
    public static String tokenText(long token) {
        return LocDictionary.tokenText(token);
    }

    /** Token eines Orts-/Bergnamens; 0 = Name wird direkt angezeigt (enthält Zeichen außerhalb A–Z, 0–9, _ und -). */
    public static long token(String name) {
        return LocDictionary.token(name);
    }

    public List<BaseMap> maps() { return maps; }

    public BaseMap find(String name) {
        for (BaseMap m : maps) if (m.name().equals(name)) return m;
        return null;
    }

    /** BaseName der Szenario-Transaktion eines TMapLoadInfo-Objekts. */
    static String clusterBaseName(Ndf n, Ndf.Obj mapLoadInfo) {
        Ndf.Value loads = n.get(mapLoadInfo, "ClusterLoads");
        if (loads == null || loads.items == null) return null;
        for (int i = 1; i < loads.items.size(); i += 2) {
            Ndf.Obj cluster = n.deref(loads.items.get(i));
            Ndf.Obj tx = cluster == null ? null : n.deref(n.get(cluster, "NdfTransaction"));
            if (tx != null) return n.getString(tx, "BaseName");
        }
        return null;
    }

    public static String gladPath(String baseName) {
        return "genglad\\" + baseName.toLowerCase() + ".cpp.gladndfbin";
    }

    /** "DataDir:\Test\Map\Chess/LevelDesign.scenario" → "test\map\chess\leveldesign.scenario". */
    public static String archivePath(String dataDirPath) {
        String p = dataDirPath.replace('/', '\\');
        if (p.regionMatches(true, 0, "DataDir:", 0, 8)) p = p.substring(8);
        while (p.startsWith("\\")) p = p.substring(1);
        return p.toLowerCase();
    }

    static boolean samePath(String a, String b) {
        return a != null && b != null && archivePath(a).equals(archivePath(b));
    }

    private static byte[] read(EdatArchive a, String path) throws IOException {
        EdatArchive.Entry e = a.find(path);
        if (e == null) throw new IOException(I18n.tr("err.not_in_archive", a.file().getFileName(), path));
        return a.read(e);
    }

    /** Lädt Szenario, Cluster, KI-Datei und Kamerapfade einer Basiskarte. */
    public BaseScenario load(BaseMap map) throws IOException {
        BaseScenario s = new BaseScenario();
        s.map = map;
        byte[] info = read(dataMap, "datasmap\\" + map.terrain().toLowerCase() + "\\mapinfo.win");
        ByteBuffer ib = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN);
        s.extentX = ib.getFloat(0x28);
        s.extentY = ib.getFloat(0x2c);

        s.clusterMap = Ndf.read(read(patchable, gladPath(map.clusterBaseName())));
        Ndf cm = s.clusterMap;
        for (Ndf.Obj o : cm.objects) {
            if (cm.className(o).equals("TScenarioLoader")) s.scenarioDataDirPath = cm.getString(o, "FileName");
            if (cm.className(o).equals("TNDFTransaction") && "MapIA".equals(cm.getString(o, "NameSpace"))) {
                s.mapIaBaseName = cm.getString(o, "BaseName");
            }
        }
        if (s.scenarioDataDirPath == null) throw new IOException(I18n.tr("err.no_scenario", map.clusterBaseName()));
        if (s.mapIaBaseName == null) throw new IOException(I18n.tr("err.no_mapia", map.clusterBaseName()));

        s.scenarioFile = read(dataMap, archivePath(s.scenarioDataDirPath));
        s.ndfStart = indexOf(s.scenarioFile, new byte[]{'E', 'U', 'G', '0'});
        if (s.ndfStart < 0) throw new IOException(I18n.tr("err.scenario_empty"));
        s.scenario = Ndf.read(java.util.Arrays.copyOfRange(s.scenarioFile, s.ndfStart, s.scenarioFile.length));

        s.mapIa = Ndf.read(read(patchable, gladPath(s.mapIaBaseName)));
        for (String str : s.mapIa.strings) {
            if (str.toLowerCase().contains("campath") && str.toLowerCase().endsWith(".ndfbin")) s.campathDataDirPath = str;
        }
        if (s.campathDataDirPath != null && dataMap.find(archivePath(s.campathDataDirPath)) != null) {
            s.campath = Ndf.read(read(dataMap, archivePath(s.campathDataDirPath)));
        }
        return s;
    }

    /** Geländenamen, auf denen Basiskarten liegen (für die Anzeige). */
    public Set<String> terrains() {
        Set<String> t = new LinkedHashSet<>();
        for (BaseMap m : maps) t.add(m.terrain());
        return t;
    }

    static int indexOf(byte[] d, byte[] p) {
        outer:
        for (int i = 0; i <= d.length - p.length; i++) {
            for (int j = 0; j < p.length; j++) if (d[i + j] != p[j]) continue outer;
            return i;
        }
        return -1;
    }
}
