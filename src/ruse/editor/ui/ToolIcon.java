package ruse.editor.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/** Kleine, gezeichnete Symbole für die Werkzeugleiste. */
final class ToolIcon implements Icon {

    private static final int S = 22;
    private final Tool tool;

    ToolIcon(Tool tool) {
        this.tool = tool;
    }

    @Override public int getIconWidth() { return S; }

    @Override public int getIconHeight() { return S; }

    @Override
    public void paintIcon(Component c, Graphics g0, int x, int y) {
        Graphics2D g = (Graphics2D) g0.create();
        g.translate(x, y);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        switch (tool) {
            case SELECT -> {
                Path2D p = new Path2D.Double();
                p.moveTo(5, 3); p.lineTo(5, 18); p.lineTo(9, 14); p.lineTo(12, 20); p.lineTo(14, 19);
                p.lineTo(11, 13); p.lineTo(16, 13); p.closePath();
                g.setColor(Color.WHITE); g.fill(p);
                g.setColor(Color.DARK_GRAY); g.setStroke(new BasicStroke(1.3f)); g.draw(p);
            }
            case START -> {
                g.setColor(MapPainter.allianceColor(1));
                g.fill(new Ellipse2D.Double(2, 2, 18, 18));
                letter(g, "1", 13f, 11, 15);
            }
            case DEPOT -> {
                g.setColor(new Color(92, 98, 60));
                g.fill(new RoundRectangle2D.Double(2, 3, 18, 16, 5, 5));
                g.setColor(new Color(235, 220, 160));
                g.setStroke(new BasicStroke(1.3f));
                g.draw(new RoundRectangle2D.Double(2, 3, 18, 16, 5, 5));
                letter(g, "25", 9f, 11, 14.5f);
            }
            case BUILDING -> {
                Path2D p = new Path2D.Double();
                p.moveTo(2, 19); p.lineTo(2, 11); p.lineTo(7, 6); p.lineTo(15, 6); p.lineTo(20, 11); p.lineTo(20, 19); p.closePath();
                g.setColor(new Color(85, 85, 90)); g.fill(p);
                g.setColor(new Color(220, 220, 220)); g.setStroke(new BasicStroke(1.3f)); g.draw(p);
                g.setColor(Color.BLACK);
                g.fill(new Rectangle2D.Double(6, 11, 10, 2.5));
            }
            case CITY -> {
                g.setColor(new Color(150, 60, 45));
                g.fill(new Rectangle2D.Double(2, 9, 6, 10));
                g.fill(new Rectangle2D.Double(9, 4, 6, 15));
                g.fill(new Rectangle2D.Double(16, 11, 5, 8));
                g.setColor(new Color(255, 230, 150));
                g.fill(new Rectangle2D.Double(11, 7, 2, 2));
                g.fill(new Rectangle2D.Double(11, 12, 2, 2));
            }
            case MOUNTAIN -> {
                Path2D p = new Path2D.Double();
                p.moveTo(1, 19); p.lineTo(8, 6); p.lineTo(12, 12); p.lineTo(15, 8); p.lineTo(21, 19); p.closePath();
                g.setColor(new Color(140, 115, 85)); g.fill(p);
                Path2D snow = new Path2D.Double();
                snow.moveTo(6, 10); snow.lineTo(8, 6); snow.lineTo(10, 9.5); snow.closePath();
                g.setColor(Color.WHITE); g.fill(snow);
            }
        }
        g.dispose();
    }

    private static void letter(Graphics2D g, String s, float size, float cx, float baseline) {
        g.setColor(Color.WHITE);
        g.setFont(g.getFont().deriveFont(Font.BOLD, size));
        int w = g.getFontMetrics().stringWidth(s);
        g.drawString(s, cx - w / 2f, baseline);
    }
}
