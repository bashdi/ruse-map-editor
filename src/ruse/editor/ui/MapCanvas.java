package ruse.editor.ui;

import ruse.editor.i18n.I18n;
import ruse.editor.model.MapObject;
import ruse.editor.model.MapProject;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.util.function.Consumer;

/** Zeichenfläche: Übersichtskarte des Geländes mit Zoom/Verschieben, Objekte setzen, wählen, ziehen. */
public final class MapCanvas extends JComponent {

    private static final double HIT_PX = 16;

    private final EditorContext ctx;
    private double zoom = 0.0004, offX, offY;
    private double lastExtentX = -1, lastExtentY = -1;
    private Consumer<String> statusSink = s -> {};

    private int panStartX, panStartY;
    private double panOffX, panOffY;
    private boolean panning, spaceDown;
    private MapObject drag;
    private double dragDX, dragDY;
    private boolean dragCheckpointed;

    public MapCanvas(EditorContext ctx) {
        this.ctx = ctx;
        setFocusable(true);
        setOpaque(true);
        setBackground(new Color(38, 40, 44));
        ctx.onMapChanged(this::mapChanged);
        ctx.onStateChanged(this::repaint);

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { onPress(e); }
            @Override public void mouseReleased(MouseEvent e) { onRelease(); }
            @Override public void mouseDragged(MouseEvent e) { onDrag(e); }
            @Override public void mouseMoved(MouseEvent e) { report(toWorld(e)); }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { onWheel(e); }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { onKey(e, true); }
            @Override public void keyReleased(KeyEvent e) { onKey(e, false); }
        });
    }

    public void setStatusSink(Consumer<String> sink) {
        this.statusSink = sink;
    }

    // ---------------------------------------------------------------- Ansicht

    private void mapChanged() {
        MapProject m = ctx.map();
        if (m.extentX != lastExtentX || m.extentY != lastExtentY) {
            lastExtentX = m.extentX;
            lastExtentY = m.extentY;
            SwingUtilities.invokeLater(this::fit);
        }
        repaint();
    }

    public void fit() {
        MapProject m = ctx.map();
        if (m.extentX <= 0 || m.extentY <= 0) return;
        int w = Math.max(100, getWidth()), h = Math.max(100, getHeight());
        zoom = Math.min((w - 40.0) / m.extentX, (h - 40.0) / m.extentY);
        offX = -(w / zoom - m.extentX) / 2;
        offY = -(h / zoom - m.extentY) / 2;
        repaint();
    }

    public void zoomBy(double factor) {
        zoomAt(factor, getWidth() / 2.0, getHeight() / 2.0);
    }

    private void zoomAt(double factor, double sx, double sy) {
        MapProject m = ctx.map();
        double wx = sx / zoom + offX, wy = sy / zoom + offY;
        double min = m.extentX > 0 ? 0.3 * Math.min(getWidth() / m.extentX, getHeight() / m.extentY) : 1e-5;
        zoom = Math.max(min, Math.min(0.02, zoom * factor));
        offX = wx - sx / zoom;
        offY = wy - sy / zoom;
        repaint();
    }

    private MapPainter.View view() {
        return new MapPainter.View(zoom, offX, offY);
    }

    private Point2D.Double toWorld(MouseEvent e) {
        return new Point2D.Double(e.getX() / zoom + offX, e.getY() / zoom + offY);
    }

    public void toolChanged() {
        setCursor(Cursor.getPredefinedCursor(ctx.tool == Tool.SELECT ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR));
        repaint();
    }

    // ---------------------------------------------------------------- Zeichnen

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        MapProject m = ctx.map();
        if (!m.hasTerrain()) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(200, 200, 200));
            g.setFont(getFont().deriveFont(Font.PLAIN, 16f));
            String s = I18n.tr("canvas.empty");
            g.drawString(s, (getWidth() - g.getFontMetrics().stringWidth(s)) / 2, getHeight() / 2);
            g.dispose();
            return;
        }
        MapPainter.View v = view();
        MapPainter.paintBackground(g, m, ctx.minimap, v, ctx.showGrid);
        if (ctx.showRoads) MapPainter.paintRoads(g, ctx.roads, v);
        if (ctx.showGhost) MapPainter.paintGhost(g, ctx.ghost, v, ctx::displayName);
        MapPainter.paintObjects(g, m, v, ctx.selection(), ctx.previewMode, ctx.showLabels, ctx::displayName);
        g.dispose();
    }

    // ---------------------------------------------------------------- Maus und Tastatur

    private void onPress(MouseEvent e) {
        requestFocusInWindow();
        MapProject m = ctx.map();
        if (SwingUtilities.isMiddleMouseButton(e) || SwingUtilities.isRightMouseButton(e)
                || (SwingUtilities.isLeftMouseButton(e) && spaceDown)) {
            panning = true;
            panStartX = e.getX();
            panStartY = e.getY();
            panOffX = offX;
            panOffY = offY;
            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(e) || !m.hasTerrain()) return;
        Point2D.Double w = toWorld(e);
        if (ctx.tool == Tool.SELECT) {
            MapObject hit = hitTest(w);
            ctx.select(hit);
            drag = hit;
            dragCheckpointed = false;
            if (hit != null) {
                dragDX = hit.x - w.x;
                dragDY = hit.y - w.y;
            }
            return;
        }
        if (!m.inside(w.x, w.y)) return;
        ctx.checkpoint();
        MapObject o = new MapObject(ctx.tool.kind, w.x, w.y);
        switch (o.kind) {
            case START -> {
                o.alliance = ctx.activeAlliance;
                o.priority = m.nextPriority(o.alliance);
                // Hauptquartier zur Kartenmitte ausrichten
                o.rotation = Math.toDegrees(Math.atan2(m.extentY / 2 - w.y, m.extentX / 2 - w.x));
            }
            case DEPOT -> {
                o.supply = ctx.newSupply;
                if (!e.isShiftDown()) ctx.roads.placeBesideRoad(o, w.x, w.y);
            }
            case BUILDING -> {
                o.buildingClass = ctx.newBuildingClass;
                if (!e.isShiftDown()) ctx.roads.alignToRoad(o);
            }
            case CITY -> o.name = ctx.newCityName.isBlank() ? I18n.tr("default.city") : ctx.newCityName;
            case MOUNTAIN -> o.name = ctx.newMountainName.isBlank() ? I18n.tr("default.mountain") : ctx.newMountainName;
        }
        m.objects.add(o);
        ctx.select(o);
        ctx.changed();
    }

    private MapObject hitTest(Point2D.Double w) {
        double tol = HIT_PX / zoom;
        MapObject best = null;
        double bestD = Double.MAX_VALUE;
        for (MapObject o : ctx.map().objects) {
            double d = Math.hypot(o.x - w.x, o.y - w.y);
            double bonus = o.kind == MapObject.Kind.START ? 0.8 : o.kind == MapObject.Kind.DEPOT ? 0.9 : 1.0;
            if (d < tol && d * bonus < bestD) {
                bestD = d * bonus;
                best = o;
            }
        }
        return best;
    }

    private void onRelease() {
        if (panning) {
            panning = false;
            toolChanged();
        }
        drag = null;
    }

    private void onDrag(MouseEvent e) {
        if (panning) {
            offX = panOffX - (e.getX() - panStartX) / zoom;
            offY = panOffY - (e.getY() - panStartY) / zoom;
            repaint();
            return;
        }
        Point2D.Double w = toWorld(e);
        report(w);
        if (drag == null) return;
        if (!dragCheckpointed) {
            ctx.checkpoint();
            dragCheckpointed = true;
        }
        MapProject m = ctx.map();
        double nx = Math.max(0, Math.min(m.extentX, w.x + dragDX)), ny = Math.max(0, Math.min(m.extentY, w.y + dragDY));
        drag.x = nx;
        drag.y = ny;
        // Depots folgen beim Ziehen der Straße (Umschalt = frei)
        if (drag.kind == MapObject.Kind.DEPOT && !e.isShiftDown()) ctx.roads.placeBesideRoad(drag, w.x, w.y);
        ctx.changed();
    }

    private void onWheel(MouseWheelEvent e) {
        zoomAt(Math.pow(1.15, -e.getPreciseWheelRotation()), e.getX(), e.getY());
    }

    private void onKey(KeyEvent e, boolean down) {
        if (e.getKeyCode() == KeyEvent.VK_SPACE) {
            spaceDown = down;
            return;
        }
        if (!down) return;
        MapObject s = ctx.selection();
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> ctx.select(null);
            case KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> deleteSelection();
            case KeyEvent.VK_Q, KeyEvent.VK_E -> {
                if (s != null && (s.kind == MapObject.Kind.START || s.kind == MapObject.Kind.DEPOT
                        || s.kind == MapObject.Kind.BUILDING)) {
                    ctx.checkpoint();
                    s.rotation = normAngle(s.rotation + (e.getKeyCode() == KeyEvent.VK_Q ? -15 : 15));
                    ctx.changed();
                }
            }
            default -> { }
        }
    }

    static double normAngle(double a) {
        return ((a + 180) % 360 + 360) % 360 - 180;
    }

    private void report(Point2D.Double w) {
        MapProject m = ctx.map();
        if (!m.hasTerrain() || !m.inside(w.x, w.y)) {
            statusSink.accept(" ");
            return;
        }
        statusSink.accept(String.format("X %.2f km   Y %.2f km", w.x / MapPainter.UNITS_PER_KM, w.y / MapPainter.UNITS_PER_KM));
    }

    public void deleteSelection() {
        MapObject s = ctx.selection();
        if (s == null) return;
        ctx.checkpoint();
        ctx.map().objects.remove(s);
        if (s.kind == MapObject.Kind.START) ctx.map().renumber(s.alliance);
        ctx.select(null);
        ctx.changed();
    }
}
