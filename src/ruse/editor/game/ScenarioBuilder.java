package ruse.editor.game;

import ruse.editor.model.MapObject;
import ruse.editor.model.MapProject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Übersetzt zwischen Editor-Objekten und dem Szenario-Format von R.U.S.E.
 * Spielkoordinaten: x nach rechts, y nach unten (wie die Übersichtskarte), z = Geländehöhe.
 */
public final class ScenarioBuilder {

    static final String DEPOT_CLASS = "front.batiment_depot.DalleBatimentDepot";
    /** Kameraabstand und -höhe über dem Startplatz (Median der Originalkarten). */
    private static final float CAMERA_DISTANCE = 275000f, CAMERA_HEIGHT = 187000f, CAMERA_SITE = -36.5f;

    private ScenarioBuilder() {}

    /** Ein Objekt aus einem Szenario. {@code priority} 0 = nicht angegeben. */
    public record Item(MapObject.Kind kind, float x, float y, float z, float rotation, int alliance, int priority,
                       int supply, String name, float[] camera, float azimut, float site) {}

    // ------------------------------------------------------------------ Lesen

    public static List<Item> readItems(Ndf n) {
        List<Item> out = new ArrayList<>();
        for (Ndf.Obj o : n.objects) {
            if (!n.className(o).equals("TGameDesignItem")) continue;
            Ndf.Value pos = n.get(o, "Position");
            Ndf.Obj add = n.deref(n.get(o, "AddOn"));
            if (pos == null || add == null) continue;
            float[] p = pos.asVec3();
            Ndf.Value rot = n.get(o, "Rotation");
            float r = rot == null ? 0 : rot.asFloat();
            switch (n.className(add)) {
                case "TGameDesignAddOn_StartingPoint" -> {
                    Ndf.Value cam = n.get(add, "PositionCamera");
                    out.add(new Item(MapObject.Kind.START, p[0], p[1], p[2], r, intOr(n.get(add, "AllianceNum"), 1),
                            intOr(n.get(add, "AlliancePriority"), 0), 0, "", cam == null ? null : cam.asVec3(),
                            floatOr(n.get(add, "Azimut"), 0), floatOr(n.get(add, "Site"), CAMERA_SITE)));
                }
                case "TGameDesignAddOn_Spawn" -> {
                    String py = n.getString(add, "PythonClassName");
                    boolean depot = DEPOT_CLASS.equals(py);
                    String building = depot ? null : ruse.editor.model.Buildings.classFromPython(py);
                    // Gebäude anderer Lager kommen im Gefecht nicht vor – nur neutrale übernehmen
                    Ndf.Value camp = n.get(add, "Camp");
                    boolean neutral = camp == null || GameCatalog.intOf(camp) < 0;
                    MapObject.Kind k = depot ? MapObject.Kind.DEPOT : building != null && neutral ? MapObject.Kind.BUILDING : null;
                    out.add(new Item(k, p[0], p[1], p[2], r, 0, 0,
                            intOr(n.get(add, "ChampInteger"), MapObject.DEFAULT_SUPPLY), building == null ? "" : building,
                            null, 0, 0));
                }
                case "TGameDesignAddOn_LabelVille", "TGameDesignAddOn_LabelMontagne" -> {
                    String name = n.getString(add, "ChampTexte");
                    MapObject.Kind k = n.className(add).endsWith("Ville") ? MapObject.Kind.CITY : MapObject.Kind.MOUNTAIN;
                    out.add(new Item(k, p[0], p[1], p[2], r, 0, 0, 0, name == null ? "" : name, null, 0, 0));
                }
                default -> out.add(new Item(null, p[0], p[1], p[2], r, 0, 0, 0, "", null, 0, 0));
            }
        }
        return out;
    }

    private static int intOr(Ndf.Value v, int def) {
        return v == null ? def : GameCatalog.intOf(v);
    }

    private static float floatOr(Ndf.Value v, float def) {
        return v == null ? def : v.asFloat();
    }

    /** Objekte einer Originalkarte als Editor-Objekte (Reihenfolgen je Bündnis lückenlos ab 1). */
    public static List<MapObject> toEditorObjects(GameCatalog.BaseScenario base) {
        List<MapObject> out = new ArrayList<>();
        List<Item> starts = new ArrayList<>();
        for (Item it : readItems(base.scenario)) {
            if (it.kind() == null) continue;
            if (it.kind() == MapObject.Kind.START) {
                starts.add(it);
                continue;
            }
            MapObject o = new MapObject(it.kind(), it.x(), it.y());
            o.supply = it.supply();
            o.rotation = Math.toDegrees(it.rotation());
            if (it.kind() == MapObject.Kind.BUILDING) o.buildingClass = it.name();
            else o.name = it.name();
            out.add(o);
        }
        starts.sort(Comparator.comparingInt(Item::alliance).thenComparingInt(Item::priority));
        int lastAlliance = -1, rank = 0;
        for (Item it : starts) {
            rank = it.alliance() == lastAlliance ? rank + 1 : 1;
            lastAlliance = it.alliance();
            MapObject o = new MapObject(MapObject.Kind.START, it.x(), it.y());
            o.alliance = it.alliance();
            o.priority = rank;
            o.rotation = Math.toDegrees(it.rotation());
            out.add(o);
        }
        return out;
    }

    // ------------------------------------------------------------------ Höhen

    /** Geländehöhe an (x, y), geschätzt aus den Objekten der Basiskarte (inverse Distanzgewichtung). */
    static float height(List<Item> known, float x, float y) {
        double num = 0, den = 0;
        for (Item k : known) {
            double d2 = (k.x() - x) * (double) (k.x() - x) + (k.y() - y) * (double) (k.y() - y);
            if (d2 < 1) return k.z();
            double w = 1 / (d2 * d2);
            num += w * k.z();
            den += w;
        }
        return den == 0 ? 0 : (float) (num / den);
    }

    /** Name des Kameraflugs eines Startplatzes. */
    static String warmupName(MapObject s) {
        return s.priority <= 1 ? "Warmup_J" + s.alliance : "Warmup_J" + s.alliance + "_" + s.priority;
    }

    // ------------------------------------------------------------------ Schreiben

    /** Erzeugt die neue .scenario-Datei: Zonen der Basiskarte, Objekte aus dem Editor. */
    public static byte[] buildScenario(GameCatalog.BaseScenario base, MapProject m) {
        List<Item> known = readItems(base.scenario);
        // Kamera der Originalkarte je (Bündnis, Rang) – wird mit dem Startplatz mitverschoben
        Map<String, Item> baseCams = new HashMap<>();
        Map<Integer, Integer> rank = new HashMap<>();
        known.stream().filter(k -> k.kind() == MapObject.Kind.START && k.camera() != null)
                .sorted(Comparator.comparingInt(Item::alliance).thenComparingInt(Item::priority))
                .forEach(k -> baseCams.put(k.alliance() + "/" + rank.merge(k.alliance(), 1, Integer::sum), k));
        float cx = base.extentX / 2, cy = base.extentY / 2;

        Ndf n = new Ndf();
        n.compressed = false;
        for (String c : new String[]{"TGameDesignItemList", "TGameDesignItem", "TGameDesignAddOn_LabelVille",
                "TGameDesignAddOn_Spawn", "TGameDesignAddOn_StartingPoint", "TGameDesignAddOn_LabelMontagne"}) {
            n.classes.add(c);
        }
        String[][] props = {{"GameDesignItemList", "0"}, {"Position", "1"}, {"AddOn", "1"}, {"Rotation", "1"},
                {"ChampTexte", "2"}, {"Camp", "3"}, {"PythonClassName", "3"}, {"ChampInteger", "3"},
                {"AllianceNum", "4"}, {"PositionCamera", "4"}, {"WarmupCamPath", "4"}, {"Azimut", "4"}, {"Site", "4"},
                {"AlliancePriority", "4"}, {"ChampTexte", "5"}};
        for (String[] p : props) n.properties.add(new Ndf.Property(p[0], Integer.parseInt(p[1])));
        n.topo.add(0);

        List<MapObject> objs = new ArrayList<>(m.objects);
        int count = objs.size();
        Ndf.Obj list = new Ndf.Obj(0);
        List<Ndf.Value> refs = new ArrayList<>();
        for (int i = 0; i < count; i++) refs.add(Ndf.Value.obj(1 + i, 1));
        list.props.add(new Ndf.Prop(0, Ndf.Value.list(refs)));
        n.objects.add(list);

        List<Ndf.Obj> addons = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MapObject o = objs.get(i);
            float gx = (float) o.x, gy = (float) o.y, gz = height(known, gx, gy);
            Ndf.Obj item = new Ndf.Obj(1);
            item.props.add(new Ndf.Prop(1, Ndf.Value.vec3(gx, gy, gz)));
            float rot = (float) Math.toRadians(o.rotation);
            if (rot != 0 && (o.kind == MapObject.Kind.DEPOT || o.kind == MapObject.Kind.START
                    || o.kind == MapObject.Kind.BUILDING)) {
                item.props.add(new Ndf.Prop(3, Ndf.Value.f32(rot)));
            }
            int addIndex = 1 + count + i;
            Ndf.Obj add;
            switch (o.kind) {
                case CITY, MOUNTAIN -> {
                    boolean city = o.kind == MapObject.Kind.CITY;
                    add = new Ndf.Obj(city ? 2 : 5);
                    add.props.add(new Ndf.Prop(city ? 4 : 14, Ndf.Value.wstr(o.name.isBlank() ? "-" : o.name)));
                }
                case BUILDING -> {
                    // Im Gefecht erzeugt das Spiel nur Objekte des neutralen Lagers (Camp = -1, wie bei Depots);
                    // ohne Camp-Feld wird das Gebäude stillschweigend übersprungen.
                    add = new Ndf.Obj(3);
                    add.props.add(new Ndf.Prop(5, Ndf.Value.i32(-1)));
                    add.props.add(new Ndf.Prop(6, Ndf.Value.str(n.stringIndex(ruse.editor.model.Buildings.MODULE + o.buildingClass))));
                }
                case DEPOT -> {
                    add = new Ndf.Obj(3);
                    add.props.add(new Ndf.Prop(5, Ndf.Value.i32(-1)));
                    add.props.add(new Ndf.Prop(6, Ndf.Value.str(n.stringIndex(DEPOT_CLASS))));
                    add.props.add(new Ndf.Prop(7, Ndf.Value.i32(o.supply)));
                }
                default -> { // START
                    add = new Ndf.Obj(4);
                    float[] cam;
                    float az, site;
                    Item bs = baseCams.get(o.alliance + "/" + o.priority);
                    if (bs != null) {
                        cam = new float[]{gx + bs.camera()[0] - bs.x(), gy + bs.camera()[1] - bs.y(), gz + bs.camera()[2] - bs.z()};
                        az = bs.azimut();
                        site = bs.site();
                    } else {
                        double dx = cx - gx, dy = cy - gy, len = Math.max(1, Math.hypot(dx, dy));
                        dx /= len;
                        dy /= len;
                        cam = new float[]{(float) (gx - dx * CAMERA_DISTANCE), (float) (gy - dy * CAMERA_DISTANCE), gz + CAMERA_HEIGHT};
                        az = (float) Math.toDegrees(Math.atan2(dx, dy));
                        site = CAMERA_SITE;
                    }
                    add.props.add(new Ndf.Prop(8, Ndf.Value.i32(o.alliance)));
                    add.props.add(new Ndf.Prop(13, Ndf.Value.i32(o.priority)));
                    add.props.add(new Ndf.Prop(9, Ndf.Value.vec3(cam[0], cam[1], cam[2])));
                    add.props.add(new Ndf.Prop(10, Ndf.Value.str(n.stringIndex(warmupName(o)))));
                    add.props.add(new Ndf.Prop(11, Ndf.Value.f32(az)));
                    add.props.add(new Ndf.Prop(12, Ndf.Value.f32(site)));
                }
            }
            item.props.add(new Ndf.Prop(2, Ndf.Value.obj(addIndex, add.classIndex)));
            n.objects.add(item);
            addons.add(add);
        }
        n.objects.addAll(addons);
        byte[] ndf = n.write();
        int padded = (ndf.length + 3) & ~3;

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] src = base.scenarioFile;
        body.write(src, 26, base.ndfStart - 4 - 26); // Kopfdaten und Bluff-Zonen der Basiskarte
        le(body, padded);
        body.write(ndf, 0, ndf.length);
        for (int i = ndf.length; i < padded; i++) body.write(0);
        byte[] rest = body.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(src, 0, 10); // "SCENARIO\r\n"
        out.write(new byte[16], 0, 16);
        out.write(rest, 0, rest.length);
        return fixHash(out.toByteArray());
    }

    /**
     * Setzt die Prüfsumme im Szenario-Kopf, wie RUSE.exe sie prüft: MD5 über "SCENARIO\r\n" und alles ab Byte 28
     * (nach Hash und den zwei Ausrichtungsbytes). Bei falscher Prüfsumme verwirft das Spiel das Szenario und
     * stürzt beim Start der Partie ab.
     */
    public static byte[] fixHash(byte[] scenario) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(scenario, 0, 10);
            md.update(scenario, 28, scenario.length - 28);
            byte[] out = scenario.clone();
            System.arraycopy(md.digest(), 0, out, 10, 16);
            return out;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Ergänzt bei Gebäuden ohne Camp-Feld {@code Camp = -1} (Szenarien älterer Editor-Versionen), damit das Spiel
     * sie im Gefecht als neutrale Objekte erzeugt. Liefert die Datei unverändert, wenn nichts zu tun ist.
     */
    public static byte[] fixBuildings(byte[] scenario) throws IOException {
        int start = GameCatalog.indexOf(scenario, new byte[]{'E', 'U', 'G', '0'});
        if (start < 4) return scenario;
        Ndf n = Ndf.read(Arrays.copyOfRange(scenario, start, scenario.length));
        int spawn = n.classIndex("TGameDesignAddOn_Spawn");
        int camp = spawn < 0 ? -1 : n.propertyIndex("Camp", spawn);
        if (camp < 0) return scenario;
        boolean changed = false;
        for (Ndf.Obj o : n.objects) {
            if (o.classIndex != spawn || n.get(o, "Camp") != null) continue;
            if (ruse.editor.model.Buildings.classFromPython(n.getString(o, "PythonClassName")) == null) continue;
            o.props.add(0, new Ndf.Prop(camp, Ndf.Value.i32(-1)));
            changed = true;
        }
        if (!changed) return scenario;
        byte[] ndf = n.write();
        int padded = (ndf.length + 3) & ~3;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(scenario, 0, start - 4);
        le(out, padded);
        out.write(ndf, 0, ndf.length);
        for (int i = ndf.length; i < padded; i++) out.write(0);
        return fixHash(out.toByteArray());
    }

    /** Stimmt die Prüfsumme? */
    public static boolean hashValid(byte[] scenario) {
        return Arrays.equals(fixHash(scenario), scenario);
    }

    /** Kamerapfade für den Spielstart: vom Überblick über der Kartenmitte zur Startkamera jedes Startplatzes. */
    public static byte[] buildCampath(GameCatalog.BaseScenario base, byte[] scenarioFile) throws IOException {
        int start = GameCatalog.indexOf(scenarioFile, new byte[]{'E', 'U', 'G', '0'});
        Ndf sc = Ndf.read(Arrays.copyOfRange(scenarioFile, start, scenarioFile.length));
        Ndf n = new Ndf();
        n.compressed = base.campath == null || base.campath.compressed;
        n.classes.add("TCameraPath");
        n.classes.add("TCameraPathKey");
        n.properties.add(new Ndf.Property("PositionKeyVector", 0));
        n.properties.add(new Ndf.Property("DirectionKeyVector", 0));
        n.properties.add(new Ndf.Property("Name", 0));
        n.properties.add(new Ndf.Property("Coord", 1));

        float cx = base.extentX / 2, cy = base.extentY / 2, high = Math.max(base.extentX, base.extentY) * 0.35f;
        for (Item it : readItems(sc)) {
            if (it.kind() != MapObject.Kind.START || it.camera() == null) continue;
            MapObject s = new MapObject(MapObject.Kind.START, 0, 0);
            s.alliance = it.alliance();
            s.priority = Math.max(1, it.priority());
            float[] cam = it.camera();
            float[][] keys = {{cx, cy, high}, cam, norm(it.x() - cx, it.y() - cy, it.z() - high),
                    norm(it.x() - cam[0], it.y() - cam[1], it.z() - cam[2])};
            int i = n.objects.size();
            Ndf.Obj path = new Ndf.Obj(0);
            path.props.add(new Ndf.Prop(0, Ndf.Value.list(List.of(Ndf.Value.obj(i + 1, 1), Ndf.Value.obj(i + 2, 1)))));
            path.props.add(new Ndf.Prop(1, Ndf.Value.list(List.of(Ndf.Value.obj(i + 3, 1), Ndf.Value.obj(i + 4, 1)))));
            path.props.add(new Ndf.Prop(2, Ndf.Value.str(n.stringIndex(warmupName(s)))));
            n.objects.add(path);
            n.topo.add(i);
            for (float[] k : keys) {
                Ndf.Obj key = new Ndf.Obj(1);
                key.props.add(new Ndf.Prop(3, Ndf.Value.vec3(k[0], k[1], k[2])));
                n.objects.add(key);
            }
        }
        return n.write();
    }

    private static float[] norm(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return l == 0 ? new float[]{0, 0, -1} : new float[]{x / l, y / l, z / l};
    }

    private static void le(ByteArrayOutputStream o, int v) {
        o.write(v);
        o.write(v >>> 8);
        o.write(v >>> 16);
        o.write(v >>> 24);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
