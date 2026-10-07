package ruse.editor.ui;

import ruse.editor.model.MapObject;
import ruse.editor.model.MapProject;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Zeichnet Übersichtskarte und Kartenobjekte – für Editor, Bibliotheksvorschau und PNG-Export. */
public final class MapPainter {

    /** Farben der Bündnisse bzw. Teams 1–8. */
    public static final Color[] ALLIANCE_COLORS = {
            new Color(130, 130, 130),
            new Color(45, 100, 215), new Color(210, 45, 45), new Color(40, 160, 70), new Color(230, 170, 20),
            new Color(145, 70, 190), new Color(20, 170, 185), new Color(235, 115, 30), new Color(220, 85, 165)
    };
    /** Spieleinheiten je Kilometer (Rasterweite). */
    public static final double UNITS_PER_KM = 100000;

    private MapPainter() {}

    public static Color allianceColor(int a) {
        return ALLIANCE_COLORS[Math.max(0, Math.min(ALLIANCE_COLORS.length - 1, a))];
    }

    /** Abbildung Spieleinheiten → Bildschirm. */
    public record View(double zoom, double offX, double offY) {
        public double sx(double wx) { return (wx - offX) * zoom; }
        public double sy(double wy) { return (wy - offY) * zoom; }
    }

    /** Was an einem Startplatz angezeigt wird. */
    private record StartLabel(String text, Color color, boolean used, boolean showPriority) {}

    public static void paintBackground(Graphics2D g, MapProject m, BufferedImage minimap, View v, boolean grid) {
        int x0 = (int) Math.round(v.sx(0)), y0 = (int) Math.round(v.sy(0));
        int w = (int) Math.round(m.extentX * v.zoom()), h = (int) Math.round(m.extentY * v.zoom());
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRect(x0 + 4, y0 + 4, w, h);
        if (minimap != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(minimap, x0, y0, w, h, null);
        } else {
            g.setColor(new Color(90, 105, 80));
            g.fillRect(x0, y0, w, h);
        }
        if (grid) {
            g.setColor(new Color(255, 255, 255, 45));
            for (double x = UNITS_PER_KM; x < m.extentX; x += UNITS_PER_KM) {
                int px = (int) Math.round(v.sx(x));
                g.drawLine(px, y0, px, y0 + h);
            }
            for (double y = UNITS_PER_KM; y < m.extentY; y += UNITS_PER_KM) {
                int py = (int) Math.round(v.sy(y));
                g.drawLine(x0, py, x0 + w, py);
            }
        }
        g.setColor(new Color(0, 0, 0, 200));
        g.drawRect(x0, y0, w, h);
    }

    /** Straßennetz hervorgehoben: dunkle Kontur, helle Füllung. */
    public static void paintRoads(Graphics2D g, ruse.editor.model.RoadNetwork roads, View v) {
        if (roads.isEmpty()) return;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Straßenbreite ≈ 8 m, aber mindestens gut sichtbar
        float w = (float) Math.max(3, Math.min(10, 800 * v.zoom()));
        Path2D.Double path = new Path2D.Double();
        for (float[] s : roads.segments()) {
            path.moveTo(v.sx(s[0]), v.sy(s[1]));
            path.lineTo(v.sx(s[2]), v.sy(s[3]));
        }
        g.setStroke(new BasicStroke(w + 3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(40, 30, 20, 200));
        g.draw(path);
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 214, 90));
        g.draw(path);
        g.setStroke(new BasicStroke(1.5f));
    }

    /** Beschriftungen der Startplätze je nach Vorschau-Modus. */
    private static Map<MapObject, StartLabel> startLabels(MapProject m, MapProject.Mode preview) {
        Map<MapObject, StartLabel> out = new HashMap<>();
        for (MapObject o : m.objects) {
            if (o.kind != MapObject.Kind.START) continue;
            out.put(o, preview == null
                    ? new StartLabel(String.valueOf(o.alliance), allianceColor(o.alliance), true, true)
                    : new StartLabel("–", new Color(110, 110, 110), false, false));
        }
        if (preview != null) {
            for (int p = 1; p <= m.players; p++) {
                MapObject s = m.startFor(preview, p);
                if (s != null && !out.get(s).used()) {
                    out.put(s, new StartLabel("S" + p, allianceColor(MapProject.teamOf(preview, p)), true, false));
                }
            }
        }
        return out;
    }

    public static void paintObjects(Graphics2D g, MapProject m, View v, MapObject selected, MapProject.Mode preview,
                                    boolean labels, java.util.function.Function<String, String> nameDisplay) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Map<MapObject, StartLabel> sl = startLabels(m, preview);
        for (MapObject.Kind k : new MapObject.Kind[]{MapObject.Kind.MOUNTAIN, MapObject.Kind.CITY,
                MapObject.Kind.BUILDING, MapObject.Kind.DEPOT, MapObject.Kind.START}) {
            // Startplätze: unbelegte zuerst, damit belegte an gleicher Stelle oben liegen
            for (int pass = 0; pass < (k == MapObject.Kind.START ? 2 : 1); pass++) {
                for (MapObject o : m.objects) {
                    if (o.kind != k) continue;
                    if (k == MapObject.Kind.START && sl.get(o).used() != (pass == 1)) continue;
                    paintObject(g, o, v, o == selected, labels, sl.get(o), nameDisplay);
                }
            }
        }
    }

    /** Objekte der Originalkarte, halbtransparent. */
    public static void paintGhost(Graphics2D g, List<MapObject> ghost, View v,
                                  java.util.function.Function<String, String> nameDisplay) {
        Composite c = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f));
        for (MapObject o : ghost) {
            StartLabel l = o.kind == MapObject.Kind.START
                    ? new StartLabel(String.valueOf(o.alliance), allianceColor(o.alliance), true, true) : null;
            paintObject(g, o, v, false, true, l, nameDisplay);
        }
        g.setComposite(c);
    }

    private static void paintObject(Graphics2D g, MapObject o, View v, boolean selected, boolean labels,
                                    StartLabel startLabel, java.util.function.Function<String, String> nameDisplay) {
        double sx = v.sx(o.x), sy = v.sy(o.y);
        if (selected) {
            g.setColor(new Color(255, 240, 0, 220));
            g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 4}, 0));
            g.draw(new Ellipse2D.Double(sx - 20, sy - 20, 40, 40));
        }
        g.setStroke(new BasicStroke(1.5f));
        switch (o.kind) {
            case START -> {
                StartLabel l = startLabel != null ? startLabel
                        : new StartLabel(String.valueOf(o.alliance), allianceColor(o.alliance), true, true);
                double r = 13;
                direction(g, sx, sy, o.rotation, 21);
                g.setColor(new Color(0, 0, 0, 110));
                g.fill(new Ellipse2D.Double(sx - r + 2, sy - r + 2, 2 * r, 2 * r));
                g.setColor(l.color());
                g.fill(new Ellipse2D.Double(sx - r, sy - r, 2 * r, 2 * r));
                g.setColor(Color.WHITE);
                g.draw(new Ellipse2D.Double(sx - r, sy - r, 2 * r, 2 * r));
                if (l.showPriority()) {
                    centered(g, l.text(), sx, sy, 14f, Color.WHITE);
                    // Reihenfolge als kleines Abzeichen
                    double bx = sx + 9, by = sy + 9;
                    g.setColor(new Color(30, 30, 30));
                    g.fill(new Ellipse2D.Double(bx - 7, by - 7, 14, 14));
                    centered(g, String.valueOf(o.priority), bx, by, 10f, Color.WHITE);
                } else {
                    centered(g, l.text(), sx, sy, 11f, Color.WHITE);
                }
            }
            case DEPOT -> {
                double s = 20;
                direction(g, sx, sy, o.rotation, 17);
                RoundRectangle2D box = new RoundRectangle2D.Double(sx - s / 2, sy - s / 2, s, s, 5, 5);
                g.setColor(new Color(0, 0, 0, 110));
                g.fill(new RoundRectangle2D.Double(sx - s / 2 + 2, sy - s / 2 + 2, s, s, 5, 5));
                g.setColor(new Color(92, 98, 60));
                g.fill(box);
                g.setColor(new Color(235, 220, 160));
                g.draw(box);
                centered(g, String.valueOf(o.supply), sx, sy, 10f, Color.WHITE);
            }
            case BUILDING -> {
                ruse.editor.model.Buildings.Type t = ruse.editor.model.Buildings.of(o.buildingClass);
                direction(g, sx, sy, o.rotation, 18);
                Path2D p = new Path2D.Double();
                p.moveTo(sx - 11, sy + 9); p.lineTo(sx - 11, sy - 3); p.lineTo(sx - 5, sy - 9);
                p.lineTo(sx + 5, sy - 9); p.lineTo(sx + 11, sy - 3); p.lineTo(sx + 11, sy + 9); p.closePath();
                g.setColor(new Color(0, 0, 0, 110));
                g.fill(java.awt.geom.AffineTransform.getTranslateInstance(2, 2).createTransformedShape(p));
                g.setColor(switch (t.category()) {
                    case ruse.editor.model.Buildings.DEFENSE -> new Color(95, 70, 60);
                    case ruse.editor.model.Buildings.PRODUCTION -> new Color(70, 80, 95);
                    default -> new Color(85, 85, 85);
                });
                g.fill(p);
                g.setColor(new Color(230, 230, 230));
                g.draw(p);
                centered(g, t.code(), sx, sy + 1, 9.5f, Color.WHITE);
                if (labels && selected) label(g, t.toString(), sx, sy + 24, new Color(220, 220, 255), Font.PLAIN);
            }
            case CITY -> {
                g.setColor(new Color(150, 60, 45));
                g.fill(new Rectangle2D.Double(sx - 6, sy - 3, 5, 7));
                g.fill(new Rectangle2D.Double(sx, sy - 6, 6, 10));
                g.setColor(Color.WHITE);
                g.draw(new Rectangle2D.Double(sx - 6, sy - 3, 5, 7));
                g.draw(new Rectangle2D.Double(sx, sy - 6, 6, 10));
                if (labels) label(g, nameDisplay.apply(o.name), sx, sy + 18, Color.WHITE, Font.BOLD);
            }
            case MOUNTAIN -> {
                Path2D tri = new Path2D.Double();
                tri.moveTo(sx, sy - 8);
                tri.lineTo(sx + 8, sy + 6);
                tri.lineTo(sx - 8, sy + 6);
                tri.closePath();
                g.setColor(new Color(120, 95, 70));
                g.fill(tri);
                g.setColor(Color.WHITE);
                g.draw(tri);
                if (labels) label(g, nameDisplay.apply(o.name), sx, sy + 20, new Color(255, 240, 200), Font.ITALIC);
            }
        }
    }

    private static void direction(Graphics2D g, double sx, double sy, double rotationDeg, double len) {
        double a = Math.toRadians(rotationDeg);
        g.setColor(new Color(255, 255, 255, 200));
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(sx, sy, sx + Math.cos(a) * len, sy + Math.sin(a) * len));
        g.setStroke(new BasicStroke(1.5f));
    }

    private static void centered(Graphics2D g, String s, double x, double y, float size, Color c) {
        g.setFont(g.getFont().deriveFont(Font.BOLD, size));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c);
        g.drawString(s, (float) (x - fm.stringWidth(s) / 2.0), (float) (y + (fm.getAscent() - fm.getDescent()) / 2.0));
    }

    private static void label(Graphics2D g, String s, double x, double y, Color c, int style) {
        if (s == null || s.isBlank()) return;
        g.setFont(g.getFont().deriveFont(style | Font.BOLD, 12f));
        FontMetrics fm = g.getFontMetrics();
        float lx = (float) (x - fm.stringWidth(s) / 2.0);
        g.setColor(new Color(0, 0, 0, 190));
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++) if (dx != 0 || dy != 0) g.drawString(s, lx + dx, (float) y + dy);
        g.setColor(c);
        g.drawString(s, lx, (float) y);
    }

    /** Gesamtbild mit höchstens maxSize Pixeln Kantenlänge. */
    public static BufferedImage renderOverview(MapProject m, BufferedImage minimap, int maxSize, boolean labels,
                                               java.util.function.Function<String, String> nameDisplay) {
        return renderOverview(m, minimap, ruse.editor.model.RoadNetwork.EMPTY, maxSize, labels, nameDisplay);
    }

    public static BufferedImage renderOverview(MapProject m, BufferedImage minimap, ruse.editor.model.RoadNetwork roads,
                                               int maxSize, boolean labels,
                                               java.util.function.Function<String, String> nameDisplay) {
        double ex = m.extentX > 0 ? m.extentX : 1, ey = m.extentY > 0 ? m.extentY : 1;
        double zoom = maxSize / Math.max(ex, ey);
        int w = Math.max(1, (int) Math.round(ex * zoom)), h = Math.max(1, (int) Math.round(ey * zoom));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        View v = new View(zoom, 0, 0);
        paintBackground(g, m, minimap, v, false);
        if (maxSize >= 200) paintRoads(g, roads, v);
        if (maxSize >= 200) paintObjects(g, m, v, null, null, labels, nameDisplay);
        else paintMini(g, m, v);
        g.dispose();
        return out;
    }

    /** Vereinfachte Darstellung für kleine Vorschaubilder. */
    private static void paintMini(Graphics2D g, MapProject m, View v) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (MapObject o : m.objects) {
            double sx = v.sx(o.x), sy = v.sy(o.y);
            if (o.kind == MapObject.Kind.START) {
                g.setColor(allianceColor(o.alliance));
                g.fill(new Ellipse2D.Double(sx - 3, sy - 3, 6, 6));
            } else if (o.kind == MapObject.Kind.DEPOT) {
                g.setColor(new Color(235, 220, 160));
                g.fill(new Rectangle2D.Double(sx - 1.5, sy - 1.5, 3, 3));
            } else if (o.kind == MapObject.Kind.BUILDING) {
                g.setColor(new Color(60, 60, 60));
                g.fill(new Rectangle2D.Double(sx - 1.5, sy - 1.5, 3, 3));
            }
        }
    }
}
