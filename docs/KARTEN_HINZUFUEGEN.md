# Wie R.U.S.E. Karten lädt – Analyse

Stand: 2026-10-07, Steam-Version mit Datenordner `Data\PC\190852`.

## 1. Archive

Alle Spieldaten liegen in EDAT-Archiven (Signatur `edat`, Version 1). Lesen: `tools/edat.py`.

| Archiv | Kopf-Prüfsumme | Inhalt für Karten |
|---|---|---|
| `Data\PC\190852\ZZ_GladPatchableWin.dat` | 0 (keine) | Kartenliste, Cluster-Definitionen je Gelände und Szenario |
| `Data\PC\190852\ZZ_GladNotPatchableWin.dat` | 0 | zweite, identische Kopie der Kartenliste |
| `Data\PC\190852\DataMap_Win.dat` | 0 | `datasmap\<gelände>\mapinfo.win`, Szenarien, Bluff-Zonen, Kamerapfade |
| `Data\PC\190852\ZZ_Win.dat` | 0 | Minimaps (`.tgv`), Umgebungstexturen, Sounds, Lokalisierung |
| `Data\PC\190852\IA_Common.dat` | 0 | Skripte nur für Challenge-/Kampagnenkarten |
| `Maps\PC\DataMap<Gelände>_v09.dat` | MD5-artig, inhaltsbezogen | kompiliertes Gelände |

Das Spiel lädt die festen Namen `Data_Common`, `IA_Common`, `ZZ_GladNotPatchableWin`,
`ZZ_GladPatchableWin`, `DataMap_Win` und `ZZ_Win` (siehe Strings in `RUSE.exe`) sowie Gelände aus `Maps/PC`.
Die Datei-Prüfbytes im Wörterbuch sind überall 0.

## 2. Kartenliste: `genglad\patchable\mapinfo.cpp.gladndfbin`

Komprimiertes NDF (`EUG0`/`CNDF`, zlib ab 0x2C). Lesen: `tools/ndf.py`.
Für jeden Eintrag in der Kartenauswahl gibt es ein `TMapLoadInfo`-Objekt:

```
TMapLoadInfo
   Icone  = TUIResourceTexture  FileName = 'DataDir:\Test\map\Chess\Minimap.png'
   Icone2 = TUIResourceTexture  FileName = 'DataDir:\Test\map\Chess\Minimap2.png'
   Name   = '(2) Face a face'              ← angezeigter Name, (n) = Spielerzahl
   Path   = 'Chess'                        ← Gelände
   RootDatapackName = 'Chess'              ← → Maps\PC\DataMapChess_v09.dat
   GUID   = 16 Byte
   MapTypeMask = 16                        ← 16 = Mehrspieler/Gefecht, 1 = Tech/versteckt
   BakeThisMap = 1
   Show   = Referenz ($ = immer, VersionOption = nur Entwicklerversion)
   ClusterLoads = {'Std': …, 'WithoutRun': …}
       → TNDFTransaction BaseName = 'Patchable\Scenario\Chess\Scenario\ClusterMap'
```

`BaseName` → Archivpfad: `genglad\` + Kleinbuchstaben(BaseName) + `.cpp.gladndfbin`.

Mehrere Einträge können sich ein Gelände teilen, z. B. „(2) Face a face“, „(4) Face a face 2v2“
und „Challenge … Seelow“ auf `Chess`. Eugen hat außerdem das Gelände `Gamma` 1:1 als
`Gam_Ostfriesland` kopiert (identische `DataMap…_v09.dat`).

## 3. Szenario (Startplätze, Depots, Städte)

`genglad\patchable\scenario\<gelände>\<szenario>\clustermap.cpp.gladndfbin` verweist auf
`DataDir:\Test\Map\<Gelände>/LevelDesign.scenario`, `mapia.cpp.gladndfbin` auf
`CamPath\CamPaths_LevelDesign.ndfbin` und `ZoneBluff\LevelDesign.Kdt`. Alle drei liegen in
`DataMap_Win.dat` unter `test\map\<gelände>\…`.

Aufbau von `LevelDesign.scenario`:

```
"SCENARIO\r\n"  16 Byte MD5("SCENARIO\r\n" + Datei ab Byte 28)  2 Byte Ausrichtung
Zonen-Block: n × AREA … (Polygonnetze der Bluff-Zonen) … "AREAEND0"
int32 Länge  +  unkomprimiertes NDF:
   TGameDesignItemList → TGameDesignItem { Position (x,y,z), Rotation, AddOn }
      AddOn = TGameDesignAddOn_StartingPoint { AllianceNum, PositionCamera, WarmupCamPath, Azimut, Site }
            | TGameDesignAddOn_Spawn { Camp = -1, PythonClassName = 'front.batiment_depot.DalleBatimentDepot', ChampInteger = 25 }   ← Nachschubdepot
            | TGameDesignAddOn_LabelVille { ChampTexte = 'Dover' }                                                                   ← Stadtname
```

Koordinaten in Spieleinheiten (Chess ist 1 310 720 Einheiten breit, laut `mapinfo.win`).

## 4. Gelände (`Path`)

Ein Gelände braucht:

- `Maps\PC\DataMap<Name>_v09.dat`: `highdef.tms`/`lowdef.tms` (TMSG-Terrain-Mesh), Texturen `*.tgv_pc`,
  `staticmeshes.spkpc`, `save.boobspc` (Objektdatenbank), `occlusioninfo_*.kdt`, Wasser, `terrain.png`
- `DataMap_Win.dat`: `datasmap\<name>\mapinfo.win` (`INFOIA`, KI/Wegfindung)
- `ZZ_GladPatchableWin.dat`: `genglad\patchable\map\<name>\*.cpp.gladndfbin` (Konstanten, Licht, Wasser, Terrain)
- `ZZ_Win.dat`: Minimaps, Umgebungstexturen, HQ/Himmel-Packs, Sound

Alles davon ist kompiliert und undokumentiert.

## 5. Folgerungen

| Ziel | Machbar? | Was zu tun ist |
|---|---|---|
| Neuer Karteneintrag auf vorhandenem Gelände mit eigenen Startplätzen/Depots/Städten | **ja** | Szenario-Dateien kopieren und ändern, `TMapLoadInfo` ergänzen, Archive neu packen |
| Vorhandenes Gelände unter neuem Namen klonen | ja (reines Kopieren/Umbenennen) | wie Eugen bei `Gam_Ostfriesland` |
| Wirklich neues Gelände (eigene Berge, Flüsse, Straßen) | nein (vorerst) | Terrain-Compiler für TMS, TGV, `mapinfo.win`, KDT, BOOBS müsste nachgebaut werden |

Geänderte Archive brechen die Mehrspieler-Kompatibilität mit Spielern ohne den Mod
(so beim „Campaign Map Pack“ von Prolution, das ebenfalls `DataMap_Win.dat` und
`ZZ_GladPatchableWin.dat` ersetzt). Steam „Dateien überprüfen“ stellt den Originalzustand wieder her.

## 5b. Gefechtsliste und Kartennamen (Nachtrag)

`mapinfo.cpp` allein reicht nicht: In der Gefechts-/Mehrspielerauswahl erscheinen nur Karten, die in
`genglad\patchable\misc\globals.cpp.gladndfbin` stehen:

```
TMultiPackManager.MultiPackList → TMultiPack.MultiList → TMultiMapInfo
   GUID        = GUID des TMapLoadInfo
   Description = Lokalisierungs-Token (8 Byte) → angezeigter Name
   TrackingId, NbPlayers, MapSize, GameType, GameModeMulti, CategoryId,
   DispoMulti2Teams / DispoMultiFFA / DispoMulti3Teams / DispoMulti4Teams / DispoLadder1v1 / DispoLadder2v2
```

Die Namen stehen in `ZZ_Win.dat` → `genlocalisation\ww2\localisation\{dev,translations\<sprache>}\flash_txt.dic`
(Format `TRA\0`: u32 Anzahl, je Eintrag u64 Schlüssel aufsteigend + u32 Byte-Offset + u32 Länge in Zeichen,
danach UTF-16-Texte ohne Abschluss). Beispiel: Token `83503ae505000000` = „Stirn-an-Stirn“ / „Face-to-Face“.

Der Editor legt je Karte eine Kopie des `TMultiMapInfo` der Basiskarte an (neue GUID, Token
`0x7A000000xxxxxxxx`, Ranglisten-Flags entfernt) und trägt den Namen in alle 12 Wörterbücher ein.
`ZZ_Win.dat` wird dafür nicht neu geschrieben: die geänderten Wörterbücher werden angehängt und nur
deren Offset/Größe im Archivverzeichnis umgestellt (Original-Verzeichnis + Länge gesichert).

## 5c. Szenario-Prüfsumme (Hauptursache der Abstürze)

RUSE.exe (Code bei 0x14052d0a0) liest den 16-Byte-Hash, richtet auf 4 Byte aus und vergleicht mit
MD5("SCENARIO\r\n" + Datei ab Byte 28) – stimmt für alle 102 Originalszenarien. Bei falschem Hash wird das
Szenario verworfen; `leveldesign\launcheffetmap.py::__initialize_world` iteriert danach über die verworfene
`GameDesignDatabase`, und das Spiel stürzt ab (RUSE.exe+0x89780d, Iterator auf freigegebenem Speicher).

## 5d. Städtenamen

`leveldesign\helper.py` (`TagHelper.Prepare_for_Save`) wandelt `ChampTexte` jeder `LabelVille` in ein Token um:
die ersten 10 Zeichen aus `-0-9A-Z_a-z`, je 6 Bit. Ist das Token ≠ 0, wird der Name über
`_misc.GetVilleMultiLocalizedString(token)` aus `ville_multi.dic` (ZZ_Win.dat) geholt – ein unbekanntes Token
lässt das Spiel abstürzen. Enthält der Name ein anderes Zeichen (Leerzeichen, Umlaut), ist das Token 0 und der
Text wird direkt angezeigt. Der Editor trägt daher eigene Städtenamen mit gültigem Token in alle
`ville_multi.dic` ein (nur fehlende Schlüssel, Originale bleiben unverändert).

Die Spiellogik liegt als Python-2.5-Bytecode in `ZZ_Win.dat` → `genpython\*.ipk` (EDAT), Module `*.xyz`
(Kopf `XYZ0`, dann zlib-komprimiertes marshal).

## 5e. Straßennetz und Gebäude

`datasmap\<gelände>\mapinfo.win` („INFOIA“): bei 0x34 u16 Knotenzahl, u16 Kantenzahl, danach u32-Offsets
(relativ zu 0x34) auf Knoten (u32 Kennung, f32 x, f32 y) und Kanten (u16 von, u16 nach, u16 Länge in 10er-Einheiten).
Gilt für alle 30 Mehrspielergelände. Original-Depots stehen im Median 11 850 Einheiten neben der Straßenmitte,
Drehung = Richtung zur Straße + 90° (parallel). Das Spiel schiebt Gebäude mit Bauregel „Straße“ beim Erzeugen ohnehin
selbst an die nächste Straße (`helper._adjust_unit_position_relative_to_road` → `FindBuildLocationNearestOnRoad`).

Gebäude: `TGameDesignAddOn_Spawn` mit `PythonClassName = front.parametres.Classes.Building_…` (134 Klassen in
`eugenpatchable\parametres\classes.py`). Im Gefecht ist `CampList` leer, daher erzeugt `TagHelper.Prepare` nur
neutrale Objekte. Gebäude brauchen daher ausdrücklich `Camp = -1` (wie Depots); ohne das Feld werden sie im Gefecht übersprungen (im Spiel bestätigt). „Demo – Cotentin (3v3)“ lässt `Camp` weg, läuft aber mit eigener Lagerliste.

## 6. Umsetzung im Editor

| Klasse | Aufgabe |
|---|---|
| `game/EdatArchive`, `game/EdatWriter` | EDAT lesen/schreiben – Neuaufbau aller Originalarchive ist bytegleich (Sortierung: `\` vor allen Zeichen) |
| `game/Ndf` | NDF lesen/schreiben (zlib Stufe 9 + Sync-Flush) – 886 Originaldateien bytegleich |
| `game/GameCatalog` | Basiskarten aus `mapinfo.cpp`, Szenario/Cluster/KI/Kamerapfade, Ausdehnung aus `mapinfo.win` |
| `game/ScenarioBuilder` | Szenario (Zonen der Basis + eigene Objekte) und Kamerapfade erzeugen |
| `game/MapInstaller` | Sicherung, Einfügen, Entfernen, Wiederherstellen |

Pro eigener Karte (`<id>` = `u` + 8 Hex-Zeichen) entstehen:

- `genglad\patchable\scenario\<gelände>\scenario_<id>\clustermap.cpp.gladndfbin` (Kopie der Basis, zeigt auf neues Szenario und neue KI-Datei)
- `genglad\patchable\scenario\<gelände>\scenario_<id>\mapia.cpp.gladndfbin` (Kopie der Basis, zeigt auf neue Kamerapfade; Bluff-Zonen-KDT der Basis)
- `test\map\<gelände>\leveldesign_<id>.scenario` und `test\map\<gelände>\campath\campaths_<id>.ndfbin`
- ein angehängter `TMapLoadInfo`-Eintrag (Kopie des Basiseintrags mit neuem Namen, GUID und Cluster-Pfad) in beiden `mapinfo.cpp.gladndfbin`, Index an `TOPO` angehängt

Ermittelte Konventionen:

- Spielkoordinaten: x nach rechts, y nach unten wie `terrain.png`; Editor-Zelle = 5120 Einheiten
- Startkamera: `Azimut` = Blickrichtung (atan2(dx, dy) in Grad), Kamera ≈ 275 000 Einheiten hinter und 187 000 über dem Startplatz, `Site` ≈ -36,5
- Höhe (z) neuer Objekte: inverse Distanzgewichtung aus den Objekten der Basiskarte

Offen / im Spiel zu prüfen: exakte Objekthöhen,
Anzeige des Kartennamens (wird evtl. über Lokalisierung gesucht).
