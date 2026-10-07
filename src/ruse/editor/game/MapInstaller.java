package ruse.editor.game;

import ruse.editor.i18n.I18n;
import ruse.editor.io.Json;
import ruse.editor.model.MapProject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Fügt eigene Karten in R.U.S.E. ein.
 *
 * Vor der ersten Änderung werden die drei betroffenen Archive gesichert. Jede Installation baut die
 * Archive aus diesen Originalen plus allen eigenen Karten neu auf; eigene Karten liegen fertig erzeugt
 * unter {@code <RUSE>\MapEditor\maps\<id>\}.
 */
public final class MapInstaller {

    public static final String PATCHABLE = "ZZ_GladPatchableWin.dat";
    public static final String NOTPATCHABLE = "ZZ_GladNotPatchableWin.dat";
    public static final String DATAMAP = "DataMap_Win.dat";
    private static final String[] ARCHIVES = {PATCHABLE, NOTPATCHABLE, DATAMAP};
    static final String GLOBALS = "genglad\\patchable\\misc\\globals.cpp.gladndfbin";

    /** Eine installierte eigene Karte. */
    public record CustomMap(String id, String name, String baseName, String guid, Path dir) {
        @Override public String toString() { return name; }
    }

    private final Path root;
    private final Path dataDir;
    private final Path editorDir;
    private final Path backupDir;
    private final Path mapsDir;

    public MapInstaller(RuseInstallation inst) throws IOException {
        root = inst.root();
        dataDir = findDataDir(root);
        editorDir = root.resolve("MapEditor");
        backupDir = editorDir.resolve("original").resolve(dataDir.getFileName().toString());
        mapsDir = editorDir.resolve("maps");
        zzWin = new ZzWinPatcher(dataDir.resolve(ZzWinPatcher.ARCHIVE), backupDir);
    }

    private final ZzWinPatcher zzWin;

    /** Neuester Versionsordner unter Data\PC, der die Archive enthält. */
    private static Path findDataDir(Path root) throws IOException {
        Path pc = root.resolve("Data").resolve("PC");
        try (Stream<Path> s = Files.list(pc)) {
            return s.filter(p -> p.getFileName().toString().matches("\\d+"))
                    .filter(p -> Files.isRegularFile(p.resolve(PATCHABLE)))
                    .max(Comparator.comparingLong(p -> Long.parseLong(p.getFileName().toString())))
                    .orElseThrow(() -> new IOException(I18n.tr("err.no_data_dir", PATCHABLE, pc)));
        }
    }

    public Path dataDir() { return dataDir; }

    public Path backupDir() { return backupDir; }

    private Path live(String archive) { return dataDir.resolve(archive); }

    private Path backup(String archive) { return backupDir.resolve(archive); }

    /** Archiv in Originalfassung (Sicherung, falls vorhanden). */
    public Path original(String archive) {
        return Files.isRegularFile(backup(archive)) ? backup(archive) : live(archive);
    }

    public GameCatalog catalog() throws IOException {
        return new GameCatalog(original(PATCHABLE), original(DATAMAP), dataDir.resolve(ZzWinPatcher.ARCHIVE));
    }

    // ------------------------------------------------------------------ Sicherung

    private Path stateFile() { return editorDir.resolve("state.json"); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readState() {
        try {
            if (Files.isRegularFile(stateFile())) return (Map<String, Object>) Json.parse(Files.readString(stateFile()));
        } catch (Exception ignored) {
            // beschädigt → neu anlegen
        }
        return new LinkedHashMap<>();
    }

    private static String stamp(Path p) throws IOException {
        return Files.size(p) + "/" + Files.getLastModifiedTime(p).toMillis();
    }

    /**
     * Legt Sicherungen an. Wurde ein Archiv seit unserer letzten Änderung ersetzt (z. B. durch ein
     * Spiel-Update oder „Dateien überprüfen“), gilt die neue Datei als Original.
     */
    private void ensureBackup() throws IOException {
        Files.createDirectories(backupDir);
        Map<String, Object> state = readState();
        for (String a : ARCHIVES) {
            Object written = state.get("written:" + a);
            boolean ours = written != null && written.equals(stamp(live(a)));
            if (!Files.isRegularFile(backup(a)) || (!ours && !sameFile(live(a), backup(a)))) {
                Files.copy(live(a), backup(a), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static boolean sameFile(Path a, Path b) throws IOException {
        if (Files.size(a) != Files.size(b)) return false;
        return Files.mismatch(a, b) == -1;
    }

    // ------------------------------------------------------------------ Eigene Karten

    public List<CustomMap> installed() throws IOException {
        List<CustomMap> out = new ArrayList<>();
        if (!Files.isDirectory(mapsDir)) return out;
        try (Stream<Path> s = Files.list(mapsDir)) {
            for (Path d : s.sorted().toList()) {
                Path meta = d.resolve("meta.json");
                if (!Files.isRegularFile(meta)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(meta));
                out.add(new CustomMap((String) m.get("id"), (String) m.get("name"), (String) m.get("base"),
                        (String) m.get("guid"), d));
            }
        }
        return out;
    }

    /** Erzeugt die Dateien einer neuen Karte und baut die Spielarchive neu. Liefert die Karte. */
    public CustomMap install(MapProject project, String displayName) throws IOException {
        GameCatalog.BaseMap found = catalog().find(project.gameBase);
        if (found == null) throw new IOException(I18n.tr("err.base_not_found", project.gameBase));
        return install(project, found, displayName);
    }

    public CustomMap install(MapProject project, GameCatalog.BaseMap baseMap, String displayName) throws IOException {
        ensureBackup();
        GameCatalog catalog = catalog();
        GameCatalog.BaseScenario base = catalog.load(baseMap);

        SecureRandom rnd = new SecureRandom();
        byte[] idBytes = new byte[4];
        rnd.nextBytes(idBytes);
        String id = "u" + ScenarioBuilder.hex(idBytes);
        byte[] guid = new byte[16];
        rnd.nextBytes(guid);

        String t = baseMap.terrainFolder();
        String scenarioPath = dirOf(base.scenarioDataDirPath) + "LevelDesign_" + id + ".scenario";
        String campathPath = base.campathDataDirPath == null
                ? "DataDir:\\Test\\map\\" + t + "\\CamPath\\CamPaths_" + id + ".ndfbin"
                : dirOf(base.campathDataDirPath) + "CamPaths_" + id + ".ndfbin";
        String clusterBase = "Patchable\\Scenario\\" + t + "\\Scenario_" + id + "\\ClusterMap";
        String mapIaBase = "Patchable\\Scenario\\" + t + "\\Scenario_" + id + "\\MapIA";

        byte[] scenario = ScenarioBuilder.buildScenario(base, project);
        byte[] campath = ScenarioBuilder.buildCampath(base, scenario);

        // KI-Datei: verweist auf die neuen Kamerapfade (Bluff-Zonen bleiben die der Basiskarte)
        Ndf mapIa = base.mapIa;
        if (base.campathDataDirPath != null) repoint(mapIa, base.campathDataDirPath, campathPath);

        // Cluster: neues Szenario und neue KI-Datei
        Ndf cm = base.clusterMap;
        repoint(cm, base.scenarioDataDirPath, scenarioPath);
        for (Ndf.Obj o : cm.objects) {
            if (cm.className(o).equals("TNDFTransaction") && "MapIA".equals(cm.getString(o, "NameSpace"))) {
                setString(cm, o, "BaseName", mapIaBase);
                setString(cm, o, "OutputFileName", "Scenario\\" + t + "\\Scenario_" + id + "\\MapIA.ndfbin");
            }
        }

        Path dir = mapsDir.resolve(id);
        Files.createDirectories(dir);
        Files.write(dir.resolve("scenario.bin"), scenario);
        Files.write(dir.resolve("campath.bin"), campath);
        Files.write(dir.resolve("clustermap.bin"), cm.write());
        Files.write(dir.resolve("mapia.bin"), mapIa.write());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", id);
        meta.put("name", displayName);
        meta.put("title", stripPlayers(displayName));
        meta.put("players", project.players);
        List<Object> modeNames = new ArrayList<>();
        for (MapProject.Mode md : project.modes) modeNames.add(md.name());
        meta.put("modes", modeNames);
        meta.put("mainMode", project.mainMode.name());
        meta.put("base", baseMap.name());
        meta.put("guid", ScenarioBuilder.hex(guid));
        meta.put("terrain", baseMap.terrain());
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("scenario.bin", DATAMAP + "|" + GameCatalog.archivePath(scenarioPath));
        files.put("campath.bin", DATAMAP + "|" + GameCatalog.archivePath(campathPath));
        files.put("clustermap.bin", PATCHABLE + "|" + GameCatalog.gladPath(clusterBase));
        files.put("mapia.bin", PATCHABLE + "|" + GameCatalog.gladPath(mapIaBase));
        meta.put("files", files);
        meta.put("clusterBaseName", clusterBase);
        meta.put("clusterOutput", "map\\" + t + "\\Scenario_" + id + "\\ClusterMap.ndfbin");
        Files.writeString(dir.resolve("meta.json"), Json.write(meta), StandardCharsets.UTF_8);

        try {
            rebuild();
        } catch (IOException | RuntimeException e) {
            deleteDir(dir);
            throw e;
        }
        return new CustomMap(id, displayName, baseMap.name(), ScenarioBuilder.hex(guid), dir);
    }

    public void uninstall(CustomMap map) throws IOException {
        deleteDir(map.dir());
        rebuild();
    }

    /** Stellt die Originalarchive wieder her. Eigene Karten bleiben gespeichert, sind aber nicht mehr im Spiel. */
    public void restoreOriginals() throws IOException {
        if (!Files.isDirectory(backupDir)) return;
        for (String a : ARCHIVES) {
            if (Files.isRegularFile(backup(a))) Files.copy(backup(a), live(a), StandardCopyOption.REPLACE_EXISTING);
        }
        zzWin.restore();
        if (Files.isDirectory(mapsDir)) {
            for (CustomMap m : installed()) deleteDir(m.dir());
        }
        Files.deleteIfExists(stateFile());
    }

    // ------------------------------------------------------------------ Neuaufbau

    /** Baut die drei Archive aus den Originalen und allen eigenen Karten neu auf. */
    @SuppressWarnings("unchecked")
    public void rebuild() throws IOException {
        ensureBackup();
        List<CustomMap> maps = installed();
        Map<String, Object> state = readState();
        zzWin.ensureBackup((String) state.get("written:" + ZzWinPatcher.ARCHIVE));
        if (maps.isEmpty()) {
            for (String a : ARCHIVES) Files.copy(backup(a), live(a), StandardCopyOption.REPLACE_EXISTING);
            zzWin.restore();
            Files.deleteIfExists(stateFile());
            return;
        }
        EdatArchive patch = new EdatArchive(backup(PATCHABLE));
        EdatArchive notPatch = new EdatArchive(backup(NOTPATCHABLE));
        EdatArchive dataMap = new EdatArchive(backup(DATAMAP));

        Ndf infoP = Ndf.read(patch.read(patch.find(GameCatalog.MAPINFO_PATCHABLE)));
        Ndf infoN = Ndf.read(notPatch.read(notPatch.find(GameCatalog.MAPINFO_NOTPATCHABLE)));
        Ndf globals = Ndf.read(patch.read(patch.find(GLOBALS)));
        Map<Long, String> names = new java.util.TreeMap<>();
        Map<Long, String> cities = new java.util.TreeMap<>();
        EdatWriter wp = new EdatWriter(), wn = new EdatWriter(), wd = new EdatWriter();
        wp.putAll(patch);
        wn.putAll(notPatch);
        wd.putAll(dataMap);
        for (CustomMap m : maps) {
            Map<String, Object> meta = (Map<String, Object>) Json.parse(Files.readString(m.dir().resolve("meta.json")));
            for (Ndf info : new Ndf[]{infoP, infoN}) {
                appendEntry(info, m.baseName(), m.name(), ScenarioBuilder.unhex(m.guid()),
                        (String) meta.get("clusterBaseName"), (String) meta.get("clusterOutput"));
            }
            long locKey = locKey(m.id());
            String title = meta.get("title") instanceof String t ? t : stripPlayers(m.name());
            int players = meta.get("players") instanceof Number num ? num.intValue() : playersFromName(m.name());
            java.util.Set<MapProject.Mode> modes = null;
            if (meta.get("modes") instanceof List<?> ml) {
                modes = java.util.EnumSet.noneOf(MapProject.Mode.class);
                for (Object o : ml) modes.add(MapProject.Mode.valueOf((String) o));
            }
            MapProject.Mode main = meta.get("mainMode") instanceof String mm ? MapProject.Mode.valueOf(mm) : null;
            appendMultiInfo(globals, baseGuid(infoP, m.baseName()), ScenarioBuilder.unhex(m.guid()), locKey,
                    "MPU_" + m.id(), players, modes, main);
            names.put(locKey, title);
            // Eigene Städtenamen: das Spiel sucht sie als Token in der Ortsnamen-Tabelle (sonst Absturz)
            byte[] scen = Files.readAllBytes(m.dir().resolve("scenario.bin"));
            Ndf sn = Ndf.read(java.util.Arrays.copyOfRange(scen, GameCatalog.indexOf(scen, new byte[]{'E', 'U', 'G', '0'}), scen.length));
            for (ScenarioBuilder.Item it : ScenarioBuilder.readItems(sn)) {
                long tok = it.kind() != null && it.kind().isLabel() ? LocDictionary.token(it.name()) : 0;
                if (tok != 0) cities.putIfAbsent(tok, it.name());
            }
            Map<String, Object> files = (Map<String, Object>) meta.get("files");
            for (Map.Entry<String, Object> f : files.entrySet()) {
                String[] target = ((String) f.getValue()).split("\\|", 2);
                byte[] data = Files.readAllBytes(m.dir().resolve(f.getKey()));
                // ältere Editor-Versionen haben die Szenario-Prüfsumme falsch berechnet
                if (target[1].endsWith(".scenario")) data = ScenarioBuilder.fixHash(ScenarioBuilder.fixBuildings(data));
                EdatWriter w = target[0].equals(PATCHABLE) ? wp : target[0].equals(DATAMAP) ? wd : wn;
                w.put(EdatWriter.Item.ofBytes(target[1], data));
            }
        }
        wp.put(EdatWriter.Item.ofBytes(GameCatalog.MAPINFO_PATCHABLE, infoP.write()));
        wn.put(EdatWriter.Item.ofBytes(GameCatalog.MAPINFO_NOTPATCHABLE, infoN.write()));
        wp.put(EdatWriter.Item.ofBytes(GLOBALS, globals.write()));

        wp.write(live(PATCHABLE));
        wn.write(live(NOTPATCHABLE));
        wd.write(live(DATAMAP));
        zzWin.apply(Map.of(ZzWinPatcher.NAME_DICTIONARY_SUFFIX, names, ZzWinPatcher.CITY_DICTIONARY_SUFFIX, cities));
        for (String a : ARCHIVES) state.put("written:" + a, stamp(live(a)));
        state.put("written:" + ZzWinPatcher.ARCHIVE, zzWin.stamp());
        Files.writeString(stateFile(), Json.write(state), StandardCharsets.UTF_8);
    }

    /**
     * Hängt an die Kartenliste eine Kopie des Eintrags {@code baseName} (samt Unterobjekten) an,
     * mit neuem Namen, neuer GUID und eigenem Szenario-Cluster.
     */
    static void appendEntry(Ndf n, String baseName, String name, byte[] guid, String clusterBase, String clusterOutput)
            throws IOException {
        int cls = n.classIndex("TMapLoadInfo");
        int baseIdx = -1;
        for (int i = 0; i < n.objects.size(); i++) {
            Ndf.Obj o = n.objects.get(i);
            if (o.classIndex == cls && baseName.equals(n.getString(o, "Name"))) {
                baseIdx = i;
                break;
            }
        }
        if (baseIdx < 0) throw new IOException(I18n.tr("err.base_not_in_list", baseName));

        // Alle über Objektreferenzen erreichbaren Unterobjekte einsammeln
        java.util.TreeSet<Integer> closure = new java.util.TreeSet<>();
        java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>(List.of(baseIdx));
        while (!todo.isEmpty()) {
            int i = todo.pop();
            if (!closure.add(i)) continue;
            for (Ndf.Prop p : n.objects.get(i).props) collectRefs(p.value, todo);
        }
        Map<Integer, Integer> remap = new java.util.HashMap<>();
        int next = n.objects.size();
        for (int i : closure) remap.put(i, next++);
        List<Ndf.Obj> copies = new ArrayList<>();
        for (int i : closure) {
            Ndf.Obj c = n.objects.get(i).deepCopy();
            for (Ndf.Prop p : c.props) remapRefs(p.value, remap);
            copies.add(c);
        }
        n.objects.addAll(copies);
        Ndf.Obj entry = n.objects.get(remap.get(baseIdx));
        n.get(entry, "Name").index = n.stringIndex(name);
        Ndf.Value g = n.get(entry, "GUID");
        if (g != null) g.raw = guid.clone();
        for (Ndf.Obj c : copies) {
            if (n.className(c).equals("TNDFTransaction")) {
                setString(n, c, "BaseName", clusterBase);
                setString(n, c, "OutputFileName", clusterOutput);
            }
        }
        n.topo.add(remap.get(baseIdx));
    }

    /** Lokalisierungs-Schlüssel für den Kartennamen (eigener Bereich, kollidiert nicht mit Eugens Schlüsseln). */
    static long locKey(String id) {
        return 0x7A00000000000000L | Long.parseLong(id.substring(1), 16);
    }

    private static String stripPlayers(String name) {
        return name.replaceFirst("^\\(\\d+\\)\\s*", "");
    }

    private static int playersFromName(String name) {
        try {
            return Integer.parseInt(name.substring(1, name.indexOf(')')).trim());
        } catch (RuntimeException e) {
            return 2;
        }
    }

    private static byte[] baseGuid(Ndf mapinfo, String baseName) throws IOException {
        int cls = mapinfo.classIndex("TMapLoadInfo");
        for (Ndf.Obj o : mapinfo.objects) {
            if (o.classIndex == cls && baseName.equals(mapinfo.getString(o, "Name"))) {
                Ndf.Value g = mapinfo.get(o, "GUID");
                if (g != null) return g.raw;
            }
        }
        throw new IOException(I18n.tr("err.base_guid", baseName));
    }

    /**
     * Trägt die Karte in die Gefechts-/Mehrspielerliste ein: Kopie des {@code TMultiMapInfo} der Basiskarte mit
     * neuer GUID, eigenem Namensschlüssel und Spielerzahl, ohne Ranglisten-Freigabe, im ersten {@code TMultiPack}.
     */
    static void appendMultiInfo(Ndf g, byte[] baseGuid, byte[] guid, long locKey, String trackingId, int players,
                                java.util.Set<MapProject.Mode> modes, MapProject.Mode main) throws IOException {
        int cls = g.classIndex("TMultiMapInfo");
        Ndf.Obj base = null;
        for (Ndf.Obj o : g.objects) {
            Ndf.Value v = o.classIndex == cls ? g.get(o, "GUID") : null;
            if (v != null && java.util.Arrays.equals(v.raw, baseGuid)) {
                base = o;
                break;
            }
        }
        if (base == null) throw new IOException(I18n.tr("err.base_not_multi"));
        Ndf.Obj c = base.deepCopy();
        c.props.removeIf(p -> {
            String n = g.properties.get(p.property).name();
            return n.equals("DispoLadder1v1") || n.equals("DispoLadder2v2") || n.equals("RewardId");
        });
        g.get(c, "GUID").raw = guid.clone();
        Ndf.Value desc = g.get(c, "Description");
        if (desc != null && desc.type == Ndf.T_LOC) {
            desc.raw = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(locKey).array();
        }
        Ndf.Value track = g.get(c, "TrackingId");
        if (track != null && (track.type == Ndf.T_STR || track.type == Ndf.T_PATH)) track.index = g.stringIndex(trackingId);
        Ndf.Value nb = g.get(c, "NbPlayers");
        if (nb != null && nb.raw != null) {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(nb.raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (nb.raw.length == 4) bb.putInt(0, players);
            else if (nb.raw.length == 2) bb.putShort(0, (short) players);
            else nb.raw[0] = (byte) players;
        }
        if (modes != null) {
            setInt(g, c, "DispoMultiFFA", modes.contains(MapProject.Mode.FFA) ? 1 : null);
            setInt(g, c, "DispoMulti2Teams", modes.contains(MapProject.Mode.TEAMS2) ? 1 : null);
            setInt(g, c, "DispoMulti3Teams", modes.contains(MapProject.Mode.TEAMS3) ? 1 : null);
            setInt(g, c, "DispoMulti4Teams", modes.contains(MapProject.Mode.TEAMS4) ? 1 : null);
        }
        if (main != null) {
            setInt(g, c, "GameModeMulti", main.gameValue);
            // GameType wie bei Eugen: 1=1v1, 2=2v2, 3=3v3, 4=4v4, 5=2v2v2, 6=2v2v2v2; Jeder gegen jeden ohne
            Integer type = switch (main) {
                case TEAMS2 -> players / 2;
                case TEAMS3 -> players == 6 ? 5 : null;
                case TEAMS4 -> players == 8 ? 6 : null;
                case FFA -> null;
            };
            setInt(g, c, "GameType", type);
            // Reiter der Kartenauswahl: 1 = bis 4, 2 = bis 6, 3 = bis 8 Spieler
            setInt(g, c, "CategoryId", players <= 2 ? null : players <= 4 ? 1 : players <= 6 ? 2 : 3);
        }
        g.objects.add(c);
        int idx = g.objects.size() - 1;

        int mgr = g.classIndex("TMultiPackManager");
        for (Ndf.Obj o : g.objects) {
            if (o.classIndex != mgr) continue;
            Ndf.Value packs = g.get(o, "MultiPackList");
            Ndf.Obj pack = packs == null || packs.items.isEmpty() ? null : g.deref(packs.items.get(0));
            Ndf.Value list = pack == null ? null : g.get(pack, "MultiList");
            if (list == null) break;
            list.items.add(Ndf.Value.obj(idx, cls));
            return;
        }
        throw new IOException(I18n.tr("err.no_multi_list"));
    }

    /**
     * Setzt eine ganzzahlige Eigenschaft (oder entfernt sie bei {@code null}). Fehlt sie im Objekt, wird der Werttyp
     * von einem anderen Objekt derselben Klasse übernommen.
     */
    private static void setInt(Ndf g, Ndf.Obj o, String prop, Integer value) {
        int pi = g.propertyIndex(prop, o.classIndex);
        if (pi < 0) return;
        if (value == null) {
            o.props.removeIf(p -> p.property == pi);
            return;
        }
        Ndf.Value v = g.get(o, prop);
        if (v == null) {
            Ndf.Value template = null;
            for (Ndf.Obj other : g.objects) {
                if (other.classIndex == o.classIndex && g.get(other, prop) != null) {
                    template = g.get(other, prop);
                    break;
                }
            }
            if (template == null || template.raw == null) return;
            v = template.deepCopy();
            o.props.add(new Ndf.Prop(pi, v));
        }
        if (v.raw == null) return;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(v.raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (v.raw.length >= 4) bb.putInt(0, value);
        else if (v.raw.length == 2) bb.putShort(0, value.shortValue());
        else v.raw[0] = value.byteValue();
    }

    private static void collectRefs(Ndf.Value v, java.util.Deque<Integer> out) {
        if (v.type == Ndf.T_REF && v.refKind == Ndf.REF_OBJECT && v.refA >= 0) out.push(v.refA);
        if (v.items != null) for (Ndf.Value i : v.items) collectRefs(i, out);
    }

    private static void remapRefs(Ndf.Value v, Map<Integer, Integer> remap) {
        if (v.type == Ndf.T_REF && v.refKind == Ndf.REF_OBJECT && remap.containsKey(v.refA)) v.refA = remap.get(v.refA);
        if (v.items != null) for (Ndf.Value i : v.items) remapRefs(i, remap);
    }

    /** Setzt alle String-Werte, die auf {@code oldPath} zeigen, auf einen neuen String. */
    private static void repoint(Ndf n, String oldPath, String newPath) {
        int idx = n.stringIndex(newPath);
        for (Ndf.Obj o : n.objects) for (Ndf.Prop p : o.props) repointValue(n, p.value, oldPath, idx);
    }

    private static void repointValue(Ndf n, Ndf.Value v, String oldPath, int idx) {
        if ((v.type == Ndf.T_STR || v.type == Ndf.T_PATH) && v.index != idx
                && GameCatalog.samePath(n.strings.get(v.index), oldPath)) v.index = idx;
        if (v.type == Ndf.T_WSTR && GameCatalog.samePath(v.asWString(), oldPath)) {
            v.raw = n.strings.get(idx).getBytes(StandardCharsets.UTF_16LE);
        }
        if (v.items != null) for (Ndf.Value i : v.items) repointValue(n, i, oldPath, idx);
    }

    private static void setString(Ndf n, Ndf.Obj o, String prop, String value) {
        Ndf.Value v = n.get(o, prop);
        if (v != null && (v.type == Ndf.T_STR || v.type == Ndf.T_PATH)) v.index = n.stringIndex(value);
    }

    private static String dirOf(String dataDirPath) {
        int i = Math.max(dataDirPath.lastIndexOf('\\'), dataDirPath.lastIndexOf('/'));
        return dataDirPath.substring(0, i + 1);
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
