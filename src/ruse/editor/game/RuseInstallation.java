package ruse.editor.game;

import ruse.editor.i18n.I18n;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Zugriff auf eine installierte Kopie von R.U.S.E. */
public final class RuseInstallation {

    public static final Path DEFAULT_PATH = Path.of("D:\\SteamLibrary\\steamapps\\common\\R.U.S.E");
    private static final Pattern MAP_FILE = Pattern.compile("DataMap(.+?)_v\\d+\\.dat", Pattern.CASE_INSENSITIVE);

    public record GameMap(String name, Path file) {
        @Override public String toString() { return name; }
    }

    private final Path root;

    public RuseInstallation(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public boolean isValid() {
        return Files.isRegularFile(root.resolve("RUSE.exe")) && Files.isDirectory(root.resolve("Maps"));
    }

    public List<GameMap> maps() throws IOException {
        List<GameMap> out = new ArrayList<>();
        Path dir = root.resolve("Maps").resolve("PC");
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> {
                Matcher m = MAP_FILE.matcher(p.getFileName().toString());
                if (m.matches()) out.add(new GameMap(m.group(1), p));
            });
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** Lädt die Übersichtskarte (Minimap) einer Originalkarte. */
    public static BufferedImage loadMinimap(GameMap map) throws IOException {
        EdatArchive a = new EdatArchive(map.file());
        EdatArchive.Entry e = a.find("output\\terrain.png");
        if (e == null) throw new IOException(I18n.tr("err.no_minimap", map.file().getFileName()));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(a.read(e)));
        if (img == null) throw new IOException(I18n.tr("err.minimap_unreadable"));
        return img;
    }
}
