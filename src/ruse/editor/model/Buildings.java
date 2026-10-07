package ruse.editor.model;

import ruse.editor.i18n.I18n;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gebäudetypen, die R.U.S.E. kennt (Klassen aus {@code parametres\classes.py}). Im Szenario stehen sie als
 * {@code front.parametres.Classes.<Klasse>} mit {@code Camp = -1}; im Gefecht erzeugt das Spiel nur solche neutralen Objekte
 * (ohne Camp-Feld werden sie übersprungen).
 */
public final class Buildings {

    public static final String MODULE = "front.parametres.Classes.";
    /** Kategorien (Kennungen; Anzeigename über {@link #categoryLabel}). */
    public static final String DEFENSE = "DEFENSE", PRODUCTION = "PRODUCTION", OTHER = "OTHER";

    public record Type(String className, String label, String category, String nation, String code) {
        @Override public String toString() {
            return label + (nation.isEmpty() ? "" : " (" + nation + ")");
        }
    }

    private static final String[] CLASSES = {
            "Building_Aeroport", "Building_AeroportFR", "Building_AeroportGR", "Building_AeroportITA", "Building_AeroportLeurre",
            "Building_AeroportLeurreFR", "Building_AeroportLeurreGR", "Building_AeroportLeurreITA", "Building_AeroportLeurreUK",
            "Building_AeroportLeurreURSS", "Building_AeroportLeurre_JAP", "Building_AeroportUK", "Building_AeroportURSS",
            "Building_Aeroport_JAP", "Building_ArtillerieFieldLeger", "Building_ArtillerieFieldLourd", "Building_BatimentAdministratif",
            "Building_BatimentAdministratifFR", "Building_BatimentAdministratifGR", "Building_BatimentAdministratifITA",
            "Building_BatimentAdministratifJAP", "Building_BatimentAdministratifUK", "Building_BatimentAdministratifURSS",
            "Building_Caserne", "Building_CaserneFR", "Building_CaserneGR", "Building_CaserneITA", "Building_CaserneLeurre",
            "Building_CaserneLeurreFR", "Building_CaserneLeurreGR", "Building_CaserneLeurreITA", "Building_CaserneLeurreUK",
            "Building_CaserneLeurreURSS", "Building_CaserneLeurre_JAP", "Building_CaserneUK", "Building_CaserneURSS",
            "Building_Caserne_JAP", "Building_Def_Bunker_enterre_JAP", "Building_Def_Position_105mm_JAP",
            "Building_Def_Position_DCA_JAP", "Building_DefenseAT", "Building_DefenseATITA", "Building_DefenseArtillerieDCAUK",
            "Building_DefenseDCA", "Building_DefenseDCAMGATNest", "Building_DefenseDCAMGATNestUK", "Building_DefenseDCAMGNest",
            "Building_DefenseLegereFR", "Building_DefenseMGNestGER", "Building_DefenseMGNestGERLeurre", "Building_DefenseMaginotFR",
            "Building_DefenseSiegfriedGER", "Building_DefenseSiegfriedGERLeurre", "Building_ExperimentalFactory",
            "Building_ExperimentalFactoryFR", "Building_ExperimentalFactoryGR", "Building_ExperimentalFactoryITA",
            "Building_ExperimentalFactoryLeurre", "Building_ExperimentalFactoryLeurreGR", "Building_ExperimentalFactoryLeurreITA",
            "Building_ExperimentalFactoryLeurre_JAP", "Building_ExperimentalFactoryUK", "Building_ExperimentalFactoryURSS",
            "Building_ExperimentalFactory_JAP", "Building_Headquarter", "Building_HeadquarterFR", "Building_HeadquarterGR",
            "Building_HeadquarterITA", "Building_HeadquarterJAP", "Building_HeadquarterUK", "Building_HeadquarterURSS",
            "Building_PosteAlerteAvanceUK", "Building_TruckFactory", "Building_TruckFactoryFR", "Building_TruckFactoryGR",
            "Building_TruckFactoryITA", "Building_TruckFactoryJAP", "Building_TruckFactoryUK", "Building_TruckFactoryURSS",
            "Building_UsineAutomoteur", "Building_UsineAutomoteurFR", "Building_UsineAutomoteurGER", "Building_UsineAutomoteurITA",
            "Building_UsineAutomoteurLeurre", "Building_UsineAutomoteurLeurreGER", "Building_UsineAutomoteurLeurreITA",
            "Building_UsineAutomoteurLeurre_JAP", "Building_UsineAutomoteurUK", "Building_UsineAutomoteurURSS",
            "Building_UsineAutomoteur_JAP", "Building_Usine_Atomique_FR", "Building_Usine_Atomique_GER", "Building_Usine_Atomique_ITA",
            "Building_Usine_Atomique_JAP", "Building_Usine_Atomique_UK", "Building_Usine_Atomique_URSS", "Building_Usine_Atomique_US",
            "Building_Usine_Canon", "Building_Usine_CanonFR", "Building_Usine_CanonGR", "Building_Usine_CanonITA",
            "Building_Usine_CanonLeurre", "Building_Usine_CanonLeurreFR", "Building_Usine_CanonLeurreGR",
            "Building_Usine_CanonLeurreITA", "Building_Usine_CanonLeurreUK", "Building_Usine_CanonLeurreURSS",
            "Building_Usine_CanonLeurre_JAP", "Building_Usine_CanonUK", "Building_Usine_CanonURSS", "Building_Usine_Canon_JAP",
            "Building_VehiculeFactory", "Building_VehiculeFactoryFR", "Building_VehiculeFactoryGR", "Building_VehiculeFactoryITA",
            "Building_VehiculeFactoryLeurre", "Building_VehiculeFactoryLeurreFR", "Building_VehiculeFactoryLeurreGR",
            "Building_VehiculeFactoryLeurreITA", "Building_VehiculeFactoryLeurreUK", "Building_VehiculeFactoryLeurreURSS",
            "Building_VehiculeFactoryLeurre_JAP", "Building_VehiculeFactoryUK", "Building_VehiculeFactoryURSS",
            "Building_VehiculeFactory_JAP"
    };

    /** Grundtyp (Präfix nach "Building_") → Textschlüssel, Kategorie, Kürzel. Längste Präfixe zuerst. */
    private static final String[][] BASES = {
            {"Def_Bunker_enterre", "bunker_buried", DEFENSE, "BU"},
            {"Def_Position_105mm", "gun_105", DEFENSE, "AR"},
            {"Def_Position_DCA", "aa_position", DEFENSE, "FL"},
            {"DefenseArtillerieDCA", "artillery_aa", DEFENSE, "AR"},
            {"DefenseDCAMGATNest", "aa_mg_at_nest", DEFENSE, "FM"},
            {"DefenseDCAMGNest", "aa_mg_nest", DEFENSE, "FM"},
            {"DefenseMGNest", "mg_nest", DEFENSE, "MG"},
            {"DefenseSiegfried", "siegfried", DEFENSE, "BU"},
            {"DefenseMaginot", "maginot", DEFENSE, "BU"},
            {"DefenseLegere", "light_defense", DEFENSE, "LS"},
            {"DefenseDCA", "aa_position", DEFENSE, "FL"},
            {"DefenseAT", "at_position", DEFENSE, "AT"},
            {"ArtillerieFieldLeger", "field_artillery_light", DEFENSE, "AR"},
            {"ArtillerieFieldLourd", "field_artillery_heavy", DEFENSE, "AR"},
            {"PosteAlerteAvance", "early_warning", DEFENSE, "FW"},
            {"BatimentAdministratif", "administration", OTHER, "VW"},
            {"Headquarter", "headquarters", OTHER, "HQ"},
            {"Usine_Atomique", "atomic", OTHER, "AF"},
            {"ExperimentalFactory", "prototype", PRODUCTION, "PW"},
            {"VehiculeFactory", "tank_factory", PRODUCTION, "PZ"},
            {"UsineAutomoteur", "tank_destroyer", PRODUCTION, "PJ"},
            {"Usine_Canon", "artillery_factory", PRODUCTION, "AW"},
            {"TruckFactory", "truck_factory", PRODUCTION, "LK"},
            {"Caserne", "barracks", PRODUCTION, "KA"},
            {"Aeroport", "airfield", PRODUCTION, "FP"},
    };

    /** Nationen-Kürzel im Klassennamen → Textschlüssel. */
    private static final Map<String, String> NATIONS = new LinkedHashMap<>();

    static {
        NATIONS.put("GER", "germany");
        NATIONS.put("GR", "germany");
        NATIONS.put("URSS", "ussr");
        NATIONS.put("ITA", "italy");
        NATIONS.put("JAP", "japan");
        NATIONS.put("UK", "uk");
        NATIONS.put("US", "usa");
        NATIONS.put("FR", "france");
    }

    private static final List<Type> ALL = new ArrayList<>();
    private static final Map<String, Type> BY_CLASS = new LinkedHashMap<>();

    static {
        for (String c : CLASSES) {
            Type t = describe(c);
            ALL.add(t);
            BY_CLASS.put(c, t);
        }
        ALL.sort(Comparator.comparing(Type::category).thenComparing(Type::label).thenComparing(Type::nation));
    }

    private Buildings() {}

    public static List<Type> all() { return ALL; }

    public static List<String> categories() { return List.of(DEFENSE, PRODUCTION, OTHER); }

    public static String categoryLabel(String category) {
        return I18n.tr("building.category." + category);
    }

    /** Typ zu einem Klassennamen (auch für unbekannte Klassen, z. B. aus Originalkarten). */
    public static Type of(String className) {
        Type t = BY_CLASS.get(className);
        return t != null ? t : describe(className);
    }

    /** Klassenname aus einem PythonClassName des Szenarios, null wenn es kein Gebäude ist. */
    public static String classFromPython(String pythonClassName) {
        if (pythonClassName == null) return null;
        int i = pythonClassName.lastIndexOf('.');
        String c = pythonClassName.substring(i + 1);
        return c.startsWith("Building_") ? c : null;
    }

    private static Type describe(String className) {
        String rest = className.startsWith("Building_") ? className.substring(9) : className;
        for (String[] b : BASES) {
            if (!rest.startsWith(b[0])) continue;
            String suffix = rest.substring(b[0].length()).replace("_", "");
            boolean decoy = suffix.contains("Leurre");
            boolean fake = suffix.endsWith("Fake");
            suffix = suffix.replace("Leurre", "").replace("Fake", "");
            String nation = NATIONS.containsKey(suffix) ? I18n.tr("nation." + NATIONS.get(suffix)) : suffix;
            String label = I18n.tr("building." + b[1]) + (decoy || fake ? " " + I18n.tr("building.decoy") : "");
            return new Type(className, label, b[2], nation, b[3]);
        }
        return new Type(className, rest, OTHER, "", "??");
    }
}
