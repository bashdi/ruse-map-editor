# RUSE Map Editor

Editor for custom multiplayer maps in R.U.S.E. – written in Java (Swing, no external libraries).

## Starting

```
run.bat       – builds and starts the editor (JDK 21+)
build.bat     – build only (ruse-map-editor.jar)
```

## What makes up a map in the game

R.U.S.E. can only load terrain built by Eugen: mountains, forests, rivers, roads, bridges and towns are
precomputed 3D, texture and pathfinding data. A custom map therefore consists of the **terrain of one of the
30 original maps** plus whatever the game takes from the scenario. That is exactly what the editor edits:

| Editable | Meaning in the game |
|---|---|
| **Starting points** | per alliance (1–8), with an order. Team games: team *t* starts on alliance *t* in the given order. Free for all: player *n* starts on the first point of alliance *n*. Orientation of the headquarters; the editor generates the start camera and camera flight. |
| **Supply depots** | neutral (as in all original maps), supply (originals 15–35); they snap about 120 m beside the nearest road like in Eugen's maps, aligned parallel to it (Shift = place freely) |
| **Buildings** | 134 building types of the game (bunkers, MG/anti-tank/AA positions, factories, airfields, decoys …); always **neutral** in skirmish (`Camp = -1`, like the depots) |
| **Town and mountain names** | labels on the map; known names of the game are translated, custom names are added to the game |
| **Map selection** | name, number of players (2–8), enabled modes (2 teams, 3 teams, 4 teams, free for all) and default mode |

## Usage

1. **File → New map…** – choose a terrain (top view and game preview). Starting points, depots and names of the
   original map are taken over as a starting point.
2. Tools **Starting point (S)**, **Supply depot (D)**, **Building (G)**, **Town name (C)**, **Mountain name (B)**, **Select (1)**.
   The road network of the terrain is highlighted (Ctrl+R toggles it); *Edit → Move all depots to roads*.
   Q/E rotates, Del deletes, the mouse wheel zooms, the right mouse button pans the view.
3. On the right under **Display → Assignment: …** you can see for each mode which player starts where;
   **Check** reports missing starting points per mode. Ctrl+G shows the original layout.
4. **Game → Add to game…** (Ctrl+Shift+I) – the map appears in R.U.S.E. under skirmish/multiplayer as “(n) Name”.
5. **Game → Custom maps in game…** – remove maps, write them to the game again after an update, or restore the originals.

Maps are stored in `Documents\RUSE Map Editor\maps` as `.rusemap` files (JSON). The map library (Ctrl+L) opens,
copies, renames and deletes them. Maps from the first editor version are converted when opened (painted terrain,
roads, rivers and bridges are dropped).

## Language

The interface is **English** by default. Switch to German under **Language → Deutsch**; the editor then offers
to restart itself, and the choice is saved. All texts are in
`src/ruse/editor/i18n/messages_en.properties` and `messages_de.properties` (UTF-8, placeholders `{0}`, `{1}` …).
To add another language, create `messages_<code>.properties` and register it in `I18n.LANGUAGES`.
Town and map names from the game appear in the matching game language.

## Technical notes

- These files are changed: `Data\PC\<version>\ZZ_GladPatchableWin.dat`, `ZZ_GladNotPatchableWin.dat`, `DataMap_Win.dat`
  and (only appended to) `ZZ_Win.dat`
- Backup of the originals: `<RUSE>\MapEditor\original\<version>\`; custom maps: `<RUSE>\MapEditor\maps\`
- Online play only works with players who have the same maps installed.

Format details: [docs/ADD_MAP.md](docs/ADD_MAP.md).
