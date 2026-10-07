package ruse.editor.io;

import ruse.editor.model.MapProject;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Verwaltet den Ordner, in dem die Karten gespeichert werden. */
public final class MapLibrary {

    private final Path dir;

    public MapLibrary(Path dir) {
        this.dir = dir;
    }

    public static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), "Documents", "RUSE Map Editor", "maps");
    }

    public Path dir() {
        return dir;
    }

    public List<Path> list() throws IOException {
        Files.createDirectories(dir);
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(MapIO.EXTENSION)).forEach(out::add);
        }
        out.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return out;
    }

    public static String safeFileName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "Karte" : s;
    }

    /** Freier Dateiname für den Kartennamen im Bibliotheksordner. */
    public Path fileFor(String name) {
        String base = safeFileName(name);
        Path p = dir.resolve(base + MapIO.EXTENSION);
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(base + " (" + i + ")" + MapIO.EXTENSION);
        return p;
    }

    /** Kopiert eine Karte unter neuem Namen. */
    public Path duplicate(Path source, String newName) throws IOException {
        MapProject m = MapIO.load(source);
        m.name = newName;
        Path target = fileFor(newName);
        MapIO.save(m, target);
        return target;
    }

    /** Benennt eine Karte (Name in der Datei und Dateiname) um. */
    public Path rename(Path source, String newName) throws IOException {
        MapProject m = MapIO.load(source);
        m.name = newName;
        Path target = source.getFileName().toString().equals(safeFileName(newName) + MapIO.EXTENSION)
                ? source : fileFor(newName);
        MapIO.save(m, target);
        if (!target.equals(source)) Files.delete(source);
        return target;
    }

    /** Verschiebt eine Karte in den Papierkorb (falls unterstützt), sonst löschen. */
    public void delete(Path file) throws IOException {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            if (Desktop.getDesktop().moveToTrash(file.toFile())) return;
        }
        Files.delete(file);
    }
}
