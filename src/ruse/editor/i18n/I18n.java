package ruse.editor.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.prefs.Preferences;

/**
 * Texte der Oberfläche aus Sprachdateien ({@code messages_<sprache>.properties}, UTF-8).
 * Englisch ist Standard und Rückfall für fehlende Schlüssel. Platzhalter {0}, {1} … werden ersetzt.
 */
public final class I18n {

    /** Verfügbare Sprachen: Code und Name in der jeweiligen Sprache. */
    public record Language(String code, String name) {}

    public static final List<Language> LANGUAGES = List.of(new Language("en", "English"), new Language("de", "Deutsch"));
    public static final String DEFAULT = "en";

    private static final Preferences PREFS = Preferences.userNodeForPackage(I18n.class);
    private static final Properties FALLBACK = load(DEFAULT);
    private static String language = PREFS.get("language", DEFAULT);
    private static Properties texts = load(language);

    private I18n() {}

    private static Properties load(String lang) {
        Properties p = new Properties();
        try (InputStream in = I18n.class.getResourceAsStream("messages_" + lang + ".properties")) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // fehlende Sprachdatei → Rückfall auf Englisch
        }
        return p;
    }

    public static String language() { return language; }

    /** Speichert die Sprache; wirksam nach Neustart (bzw. sofort für neu erzeugte Texte). */
    public static void setLanguage(String code) {
        language = code;
        PREFS.put("language", code);
        texts = load(code);
    }

    /** Text zu einem Schlüssel; fehlt er, der englische Text, sonst der Schlüssel selbst. */
    public static String tr(String key, Object... args) {
        String s = texts.getProperty(key);
        if (s == null) s = FALLBACK.getProperty(key, key);
        for (int i = 0; i < args.length; i++) s = s.replace("{" + i + "}", String.valueOf(args[i]));
        return s;
    }

    /** Sprachcode der Spieltexte (Orts- und Kartennamen) passend zur Oberfläche. */
    public static String gameLanguage() {
        return switch (language) {
            case "de" -> "ger";
            case "fr" -> "fr";
            default -> "us";
        };
    }
}
