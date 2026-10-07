package ruse.editor;

import ruse.editor.ui.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Path;

/** Einstiegspunkt des RUSE Map Editors. Optionales Argument: zu öffnende .rusemap-Datei. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Standard-Look-and-Feel verwenden
            }
            MainFrame f = new MainFrame();
            f.setVisible(true);
            if (args.length > 0) f.open(Path.of(args[0]));
            else f.openLast();
        });
    }
}
