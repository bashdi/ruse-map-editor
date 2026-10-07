# RUSE Map Editor

Editor für eigene Mehrspielerkarten in R.U.S.E. – in Java (Swing, ohne externe Bibliotheken).

## Starten

```
run.bat       – baut und startet den Editor (JDK 21+)
build.bat     – nur bauen (ruse-map-editor.jar)
```

## Was eine Karte im Spiel ausmacht

R.U.S.E. kann nur Gelände laden, das Eugen gebaut hat: Berge, Wälder, Flüsse, Straßen, Brücken und Orte
sind fertig berechnete 3D-, Textur- und Wegfindungsdaten. Eine eigene Karte besteht deshalb aus dem
**Gelände einer der 30 Originalkarten** plus dem, was das Spiel aus dem Szenario übernimmt. Genau das
bearbeitet der Editor:

| Bearbeitbar | Bedeutung im Spiel |
|---|---|
| **Startplätze** | je Bündnis (1–8) mit Reihenfolge. Teamspiel: Team *t* startet auf Bündnis *t* in der angegebenen Reihenfolge. Jeder gegen jeden: Spieler *n* auf dem ersten Platz von Bündnis *n*. Ausrichtung des Hauptquartiers; Startkamera und Kameraflug erzeugt der Editor. |
| **Nachschubdepots** | neutral (wie in allen Originalkarten), Vorrat (Originale 15–35); rasten wie bei Eugen ≈120 m neben der nächsten Straße ein, parallel ausgerichtet (Umschalt = frei) |
| **Gebäude** | 134 Gebäudetypen des Spiels (Bunker, MG-/Pak-/Flakstellungen, Fabriken, Flugplätze, Attrappen …); im Gefecht immer **neutral** (`Camp = -1` wie die Depots) |
| **Stadtnamen / Bergnamen** | Beschriftungen auf der Karte; bekannte Namen des Spiels werden übersetzt, eigene werden ins Spiel eingetragen |
| **Kartenauswahl** | Name, Spielerzahl (2–8), freigegebene Modi (2 Teams, 3 Teams, 4 Teams, Jeder gegen jeden) und Standardmodus |

## Bedienung

1. **Datei → Neue Karte…** – Gelände wählen (Draufsicht und Vorschaubild des Spiels). Startplätze, Depots
   und Namen der Originalkarte werden als Ausgangspunkt übernommen.
2. Werkzeuge **Startplatz (S)**, **Nachschubdepot (D)**, **Gebäude (G)**, **Stadtname (C)**, **Bergname (B)**, **Auswählen (1)**.
   Das Straßennetz des Geländes wird hervorgehoben (Strg+R ein/aus); *Bearbeiten → Alle Depots an Straßen setzen*.
   Q/E dreht, Entf löscht, Mausrad zoomt, rechte Maustaste verschiebt die Ansicht.
3. Rechts unter **Anzeige → Belegung: …** sieht man für jeden Modus, welcher Spieler wo startet;
   **Prüfung** meldet fehlende Startplätze je Modus. Strg+G blendet die Originalbelegung ein.
4. **Spiel → Ins Spiel einfügen…** (Strg+Umschalt+I) – erscheint in R.U.S.E. unter Gefecht/Mehrspieler als „(n) Name“.
5. **Spiel → Eigene Karten im Spiel…** – entfernen, nach einem Update erneut einspielen, Originale wiederherstellen.

Karten liegen in `Dokumente\RUSE Map Editor\maps` als `.rusemap` (JSON); die Kartenbibliothek (Strg+L)
öffnet, kopiert, benennt um und löscht. Karten aus der ersten Editor-Version werden beim Öffnen umgewandelt
(gemaltes Gelände, Straßen, Flüsse und Brücken entfallen).

## Sprache / Language

Die Oberfläche ist standardmäßig **Englisch**; unter **Language → Deutsch** auf Deutsch umstellbar (Neustart des
Editors, die Einstellung wird gespeichert). Alle Texte liegen in
`src/ruse/editor/i18n/messages_en.properties` und `messages_de.properties` (UTF-8, Platzhalter `{0}`, `{1}` …).
Eine weitere Sprache: Datei `messages_<code>.properties` anlegen und in `I18n.LANGUAGES` eintragen.
Orts- und Kartennamen aus dem Spiel erscheinen in der passenden Spielsprache.

## Technik

- geändert werden `Data\PC\<Version>\ZZ_GladPatchableWin.dat`, `ZZ_GladNotPatchableWin.dat`, `DataMap_Win.dat`
  und (nur angehängt) `ZZ_Win.dat`
- Sicherung der Originale: `<RUSE>\MapEditor\original\<Version>\`, eigene Karten: `<RUSE>\MapEditor\maps\`
- online nur mit Spielern, die dieselben Karten installiert haben

Formatdetails: [docs/KARTEN_HINZUFUEGEN.md](docs/KARTEN_HINZUFUEGEN.md).
