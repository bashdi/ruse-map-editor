# How R.U.S.E. loads maps – analysis

Status: 2026-10-07, Steam version with data folder `Data\PC\190852`.

## 1. Archives

All game data is stored in EDAT archives (signature `edat`, version 1). Reader: `tools/edat.py`.

| Archive | Header checksum | Content for maps |
|---|---|---|
| `Data\PC\190852\ZZ_GladPatchableWin.dat` | 0 (none) | map list, cluster definitions per terrain and scenario |
| `Data\PC\190852\ZZ_GladNotPatchableWin.dat` | 0 | second, identical copy of the map list |
| `Data\PC\190852\DataMap_Win.dat` | 0 | `datasmap\<terrain>\mapinfo.win`, scenarios, bluff zones, camera paths |
| `Data\PC\190852\ZZ_Win.dat` | 0 | minimaps (`.tgv`), environment textures, sounds, localisation |
| `Data\PC\190852\IA_Common.dat` | 0 | scripts only for challenge/campaign maps |
| `Maps\PC\DataMap<terrain>_v09.dat` | MD5-like, content-based | compiled terrain |

The game loads the fixed names `Data_Common`, `IA_Common`, `ZZ_GladNotPatchableWin`,
`ZZ_GladPatchableWin`, `DataMap_Win` and `ZZ_Win` (see strings in `RUSE.exe`) as well as terrains from `Maps/PC`.
The file check bytes in the dictionary are 0 everywhere.

## 2. Map list: `genglad\patchable\mapinfo.cpp.gladndfbin`

Compressed NDF (`EUG0`/`CNDF`, zlib from 0x2C). Reader: `tools/ndf.py`.
Every entry in the map selection is a `TMapLoadInfo` object:

```
TMapLoadInfo
   Icone  = TUIResourceTexture  FileName = 'DataDir:\Test\map\Chess\Minimap.png'
   Icone2 = TUIResourceTexture  FileName = 'DataDir:\Test\map\Chess\Minimap2.png'
   Name   = '(2) Face a face'              ← displayed name, (n) = number of players
   Path   = 'Chess'                        ← terrain
   RootDatapackName = 'Chess'              ← → Maps\PC\DataMapChess_v09.dat
   GUID   = 16 bytes
   MapTypeMask = 16                        ← 16 = multiplayer/skirmish, 1 = tech/hidden
   BakeThisMap = 1
   Show   = reference ($ = always, VersionOption = developer version only)
   ClusterLoads = {'Std': …, 'WithoutRun': …}
       → TNDFTransaction BaseName = 'Patchable\Scenario\Chess\Scenario\ClusterMap'
```

`BaseName` → archive path: `genglad\` + lowercase(BaseName) + `.cpp.gladndfbin`.

Several entries can share a terrain, e.g. “(2) Face a face”, “(4) Face a face 2v2” and
“Challenge … Seelow” on `Chess`. Eugen also copied the terrain `Gamma` 1:1 as
`Gam_Ostfriesland` (identical `DataMap…_v09.dat`).

## 3. Scenario (starting points, depots, towns)

`genglad\patchable\scenario\<terrain>\<scenario>\clustermap.cpp.gladndfbin` points to
`DataDir:\Test\Map\<terrain>/LevelDesign.scenario`, `mapia.cpp.gladndfbin` points to
`CamPath\CamPaths_LevelDesign.ndfbin` and `ZoneBluff\LevelDesign.Kdt`. All three are stored in
`DataMap_Win.dat` under `test\map\<terrain>\…`.

Layout of `LevelDesign.scenario`:

```
"SCENARIO\r\n"  16 bytes MD5("SCENARIO\r\n" + file from byte 28)  2 bytes alignment
Zone block: n × AREA … (polygon meshes of the bluff zones) … "AREAEND0"
int32 length  +  uncompressed NDF:
   TGameDesignItemList → TGameDesignItem { Position (x,y,z), Rotation, AddOn }
      AddOn = TGameDesignAddOn_StartingPoint { AllianceNum, PositionCamera, WarmupCamPath, Azimut, Site }
            | TGameDesignAddOn_Spawn { Camp = -1, PythonClassName = 'front.batiment_depot.DalleBatimentDepot', ChampInteger = 25 }   ← supply depot
            | TGameDesignAddOn_LabelVille { ChampTexte = 'Dover' }                                                                   ← town name
```

Coordinates are in game units (Chess is 1 310 720 units wide, according to `mapinfo.win`).

## 4. Terrain (`Path`)

A terrain needs:

- `Maps\PC\DataMap<name>_v09.dat`: `highdef.tms`/`lowdef.tms` (TMSG terrain mesh), textures `*.tgv_pc`,
  `staticmeshes.spkpc`, `save.boobspc` (object database), `occlusioninfo_*.kdt`, water, `terrain.png`
- `DataMap_Win.dat`: `datasmap\<name>\mapinfo.win` (`INFOIA`, AI/pathfinding)
- `ZZ_GladPatchableWin.dat`: `genglad\patchable\map\<name>\*.cpp.gladndfbin` (constants, lighting, water, terrain)
- `ZZ_Win.dat`: minimaps, environment textures, HQ/sky packs, sound

All of this is compiled and undocumented.

## 5. Conclusions

| Goal | Feasible? | What to do |
|---|---|---|
| New map entry on an existing terrain with own starting points/depots/towns | **yes** | copy and modify the scenario files, add a `TMapLoadInfo`, repack the archives |
| Clone an existing terrain under a new name | yes (plain copying/renaming) | as Eugen did with `Gam_Ostfriesland` |
| Truly new terrain (own mountains, rivers, roads) | no (for now) | the terrain compiler for TMS, TGV, `mapinfo.win`, KDT, BOOBS would have to be rebuilt |

Modified archives break multiplayer compatibility with players who do not have the mod
(as with the “Campaign Map Pack” by Prolution, which also replaces `DataMap_Win.dat` and
`ZZ_GladPatchableWin.dat`). Steam's “Verify integrity of game files” restores the original state.

## 5b. Skirmish list and map names (addendum)

`mapinfo.cpp` alone is not enough: only maps listed in
`genglad\patchable\misc\globals.cpp.gladndfbin` appear in the skirmish/multiplayer selection:

```
TMultiPackManager.MultiPackList → TMultiPack.MultiList → TMultiMapInfo
   GUID        = GUID of the TMapLoadInfo
   Description = localisation token (8 bytes) → displayed name
   TrackingId, NbPlayers, MapSize, GameType, GameModeMulti, CategoryId,
   DispoMulti2Teams / DispoMultiFFA / DispoMulti3Teams / DispoMulti4Teams / DispoLadder1v1 / DispoLadder2v2
```

The names are stored in `ZZ_Win.dat` → `genlocalisation\ww2\localisation\{dev,translations\<language>}\flash_txt.dic`
(format `TRA\0`: u32 count, per entry u64 key in ascending order + u32 byte offset + u32 length in characters,
followed by UTF-16 texts without terminator). Example: token `83503ae505000000` = “Stirn-an-Stirn” / “Face-to-Face”.

The editor creates, per map, a copy of the `TMultiMapInfo` of the base map (new GUID, token
`0x7A000000xxxxxxxx`, ranking flags removed) and enters the name into all 12 dictionaries.
`ZZ_Win.dat` is not rewritten for this: the changed dictionaries are appended and only their
offset/size in the archive directory is updated (original directory + length are backed up).

## 5c. Scenario checksum (main cause of the crashes)

`RUSE.exe` (code at 0x14052d0a0) reads the 16-byte hash, aligns it to 4 bytes and compares it with
MD5("SCENARIO\r\n" + file from byte 28) – this matches for all 102 original scenarios. If the hash is wrong, the
scenario is discarded; `leveldesign\launcheffetmap.py::__initialize_world` then iterates over the discarded
`GameDesignDatabase`, and the game crashes (RUSE.exe+0x89780d, iterator on freed memory).

## 5d. Town names

`leveldesign\helper.py` (`TagHelper.Prepare_for_Save`) converts the `ChampTexte` of every `LabelVille` into a token:
the first 10 characters from `-0-9A-Z_a-z`, 6 bits each. If the token is ≠ 0, the name is fetched via
`_misc.GetVilleMultiLocalizedString(token)` from `ville_multi.dic` (ZZ_Win.dat) – an unknown token
makes the game crash. If the name contains another character (space, umlaut), the token is 0 and the
text is shown directly. The editor therefore enters custom town names with a valid token into all
`ville_multi.dic` files (only missing keys; the originals stay unchanged).

The game logic is stored as Python 2.5 bytecode in `ZZ_Win.dat` → `genpython\*.ipk` (EDAT), modules `*.xyz`
(header `XYZ0`, then zlib-compressed marshal data).

## 5e. Road network and buildings

`datasmap\<terrain>\mapinfo.win` (“INFOIA”): at 0x34 a u16 node count and u16 edge count, followed by u32 offsets
(relative to 0x34) to nodes (u32 id, f32 x, f32 y) and edges (u16 from, u16 to, u16 length in units of 10).
Applies to all 30 multiplayer terrains. Original depots stand at a median of 11 850 units beside the road centre,
orientation = direction to the road + 90° (parallel). The game also moves buildings with the placement rule “road”
to the nearest road when it creates them (`helper._adjust_unit_position_relative_to_road` → `FindBuildLocationNearestOnRoad`).

Buildings: `TGameDesignAddOn_Spawn` with `PythonClassName = front.parametres.Classes.Building_…` (134 classes in
`eugenpatchable\parametres\classes.py`). In skirmish `CampList` is empty, so `TagHelper.Prepare` only creates
neutral objects. Buildings therefore explicitly need `Camp = -1` (like depots); without that field they are skipped in skirmish (confirmed in the game). “Demo – Cotentin (3v3)” leaves out `Camp`, but runs with its own camp list.

## 6. Implementation in the editor

| Class | Task |
|---|---|
| `game/EdatArchive`, `game/EdatWriter` | read/write EDAT – rebuilding all original archives is byte-identical (sort order: `\` before all other characters) |
| `game/Ndf` | read/write NDF (zlib level 9 + sync flush) – 886 original files byte-identical |
| `game/GameCatalog` | base maps from `mapinfo.cpp`, scenario/cluster/AI/camera paths, extent from `mapinfo.win` |
| `game/ScenarioBuilder` | create the scenario (base zones + own objects) and camera paths |
| `game/MapInstaller` | backup, install, remove, restore |

For each custom map (`<id>` = `u` + 8 hex characters) the following are created:

- `genglad\patchable\scenario\<terrain>\scenario_<id>\clustermap.cpp.gladndfbin` (copy of the base, points to the new scenario and new AI file)
- `genglad\patchable\scenario\<terrain>\scenario_<id>\mapia.cpp.gladndfbin` (copy of the base, points to the new camera paths; bluff-zone KDT of the base)
- `test\map\<terrain>\leveldesign_<id>.scenario` and `test\map\<terrain>\campath\campaths_<id>.ndfbin`
- an appended `TMapLoadInfo` entry (copy of the base entry with new name, GUID and cluster path) in both `mapinfo.cpp.gladndfbin`, index appended to `TOPO`

Determined conventions:

- Game coordinates: x to the right, y downward as in `terrain.png`; editor cell = 5120 units
- Start camera: `Azimut` = viewing direction (atan2(dx, dy) in degrees), camera ≈ 275 000 units behind and 187 000 above the starting point, `Site` ≈ -36.5
- Height (z) of new objects: inverse distance weighting from the objects of the base map

Open / to check in the game: exact object heights,
display of the map name (possibly looked up via localisation).
