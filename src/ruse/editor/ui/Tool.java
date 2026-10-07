package ruse.editor.ui;

import ruse.editor.i18n.I18n;
import ruse.editor.model.MapObject;

/** Werkzeuge des Editors – genau die Objekte, die R.U.S.E. aus einer Karte übernimmt. */
public enum Tool {
    SELECT('1', null),
    START('S', MapObject.Kind.START),
    DEPOT('D', MapObject.Kind.DEPOT),
    BUILDING('G', MapObject.Kind.BUILDING),
    CITY('C', MapObject.Kind.CITY),
    MOUNTAIN('B', MapObject.Kind.MOUNTAIN);

    public final String label;
    public final String help;
    public final char key;
    public final MapObject.Kind kind;

    Tool(char key, MapObject.Kind kind) {
        this.label = I18n.tr("tool." + name() + ".label");
        this.help = I18n.tr("tool." + name() + ".help");
        this.key = key;
        this.kind = kind;
    }
}
