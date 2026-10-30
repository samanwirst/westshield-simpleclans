# Informational clan territories

WestShieldClanTerritories 1.0.4 adds world-specific polygon labels to the WestShield SimpleClans 2.19.2-westshield.1 fork. It targets Purpur 26.2 build 2633 / Java 25, AuthMe 6.0.1, BlueMap 5.29 and the existing GriefPrevention creative-claim configuration. Version 1.0.4 was activated on 2026-10-30 during an explicitly approved restart. The original 50-vertex Syndicate region was preserved exactly, and its new 11-vertex extension was added as a separate record.

The territory has no protection policy. Entry and exit send a brief standard Minecraft actionbar with the current clan name. Building, breaking, containers, PvP and ordinary interaction continue under the existing server rules. This plugin never uncancels another plugin's decisions. Its only block-break cancellation is for an authenticated administrator holding the explicitly tagged selection wand, to prevent the wand from breaking a selected block.

## Administrative permissions

`westshieldclans.admin` defaults to operators. Grant it explicitly through LuckPerms for an intended non-OP administrator:

```text
lp user Administrator permission set westshieldclans.admin true
```

All player commands and wand clicks additionally require AuthMe authentication. Regular players, clan leaders and players granted only the old `simpleclans.leader.create` permission cannot create clans. Native `/clan create` and its aliases use the new permission. Interactive creation rechecks authority before the final write. Existing clan membership, invitations and creative group trust remain separate.

An administrator can create a clan for an authenticated online player without changing the administrator's own membership:

```text
/clanadmin create westshield PlayerName WestShield Builders
```

The selected player must not already belong to a clan. Tags and names follow the current SimpleClans validation and configured lengths. Use the exact server name, including Floodgate's prefix for a Bedrock player. `/clan create <tag> <name>` remains available for an administrator who wants to become the initial leader personally.

## Clan colors and the web map

```text
/clanadmin color westshield #2A9D8F
```

Any six-digit RGB color is accepted. The native SimpleClans colored tag is the single persisted source of truth: the Java chat prefix, territory fill, boundary and permanent name label derive from it. Existing legacy colors remain supported. Invalid colors and non-admin changes are rejected. The same tag, membership and creative trust identities are retained.

For each public BlueMap map, the plugin publishes the world's exact polygon vertices under the `westshield-clans` marker set, labeled `Clan territories`. Fill opacity is 18%; a readable permanent clan-name label is anchored inside the polygon, including concave outlines whose geometric centroid might be outside. Areas in dimensions with no existing public map are not published onto another dimension's map. Changes, renames, reassignment and disbanding update the layer automatically.

Install `web/westshield-clans-v1.js` and `web/westshield-clans-v1.css` in BlueMap's web root and append those files to the existing `scripts` and `styles` arrays in `plugins/BlueMap/webapp.conf`. Keep the existing `westshield-map-v3` files and entries. The dedicated checkbox is available even before the first territory exists, saves the visitor's preference, follows map changes and stays synchronized with BlueMap's native layer switch. It changes only the clan layer. Current hidden player/coordinate settings, flat view, daylight and disabled coordinate-click popup remain in place.

Java and the browser map preserve the exact RGB value. Bedrock's text palette is limited; Geyser's message translator converts arbitrary RGB chat/actionbar colors to supported Bedrock colors. This is a client-format limitation, not a loss of the clan's saved color. See the [upstream serializer](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/translator/text/MessageTranslator.java). No resource pack or client mod is required.

## Selecting arbitrary boundaries

1. `/cterr wand` gives a tagged blaze rod. It is distinct from GriefPrevention's golden shovel and from ordinary blaze rods.
2. Walk around the boundary, adding vertices in order with left clicks on blocks. Either winding direction works. The last point connects to the first automatically.
3. Right click or `/cterr undo` removes the last vertex. `/cterr clear` starts again.
4. `/cterr save westshield town` saves the selection as territory `town` for clan tag `westshield`.

`/cterr point` adds the block at the administrator's feet. `/cterr point <x> <z>` is an alternative for touch input or exact administrative placement. Every vertex belongs to one world. There is no configured vertex-count cap; triangles, concave outlines and long detailed boundaries use the same polygon model. Self-intersections, repeated internal vertices and zero-area outlines are rejected instead of inventing an interior.

Coordinates are horizontal block coordinates and territory detection ignores height. A boundary block is included. Different clans may share an edge, but their interiors cannot overlap. Regions belonging to the same clan tag and founding timestamp may overlap. In a shared area, the lexically first territory ID provides the label. A clan can have multiple separate territories and territories in different worlds.

To **add** selected land, use a fresh region ID, for example `/cterr save sndct syndicate2`.
This retains every saved region and its original vertices, even when the new area
overlaps an existing region of the same clan. Do not use `reshape` to add land:
that command intentionally replaces one saved outline. The clan's TAB area is the
geometric union, so overlap counts once. BlueMap also renders the union per clan
and world, avoiding doubled tint or internal borders, while preserving any holes.
The number of saved regions remains the number of records, not connected components.

The previous 1.0.3 rejected these overlapping additions. Unsaved wand selections
are still held only in memory and disappear on logout or restart. The specific
2026-10-30 extension was recovered and saved from the administrator’s local
Replay Mod packet recording before the approved update; this is not an automatic
selection-persistence feature.

## Management

| Command | Effect |
| --- | --- |
| `/cterr selection` | Show selected world and vertex count. |
| `/cterr list [tag]` | List saved territories. |
| `/cterr info [id]` | Inspect a territory or the current position. |
| `/cterr reshape <id>` | Replace its boundary with the current selection. |
| `/cterr assign <id> <tag>` | Explicitly change its clan. |
| `/cterr delete <id>` | Delete the label without deleting the clan or builds. |
| `/cterr status` | Administrative runtime status, also available from console. |

World UUIDs prevent a newly generated world from inheriting the old world's regions. Clan founding time prevents a new clan under a reused tag from inheriting the old clan's regions. A unique founding time also preserves labels after a tag rename. Inactive territory records remain available for an explicit administrator reassignment or deletion.

Territories are stored in `plugins/WestShieldClanTerritories/territories.yml`. File edits are validated as a whole; a malformed file is retained and the plugin fails closed. Administrative changes persist before becoming active and use a temporary file plus atomic replacement where the filesystem supports it. Keep this directory with the other plugin data in backups. Selecting a boundary changes no blocks.

## Build

Build the SimpleClans fork first using `westshield/build.py`, then:

```sh
python3 westshield/territories/build.py --server-root /path/to/prepared-purpur-server
python3 westshield/territories/check_geometry.py --server-root /path/to/prepared-purpur-server
```

Output: `westshield/territories/WestShieldClanTerritories-1.0.4.jar` and SHA-256. The supplied server root must contain its `libraries`, AuthMe and BlueMap 5.29 JARs. JTS 1.20.0 is downloaded from Maven Central only if missing and checked against the committed SHA-256 lock; its classes and tagged upstream license files are embedded. No runtime download is needed. Custom source is covered by the fork's GPL-3.0; JTS retains its Eclipse licenses/notices.

## Verification

The current English presentation is recorded in [../english/README.md](../english/README.md). The original territory checkpoint is recorded in [VERIFICATION.md](VERIFICATION.md), with 22 geometry, 48 native and 25 browser checks. It retains the earlier failed-attempt qualifications and physical-client limits.

The 26 standalone checks cover concavity, both winding orders, inclusive boundaries,
invalid outlines, cross-clan overlap rejection, additive same-clan overlap with
unchanged old vertices, union area, persistence of overlapping additions, reused-tag
isolation, shared edges, world isolation, disk failures and a saved/reloaded
10,000-vertex polygon. The live activation separately confirmed the 1.0.4 startup, four saved regions,
both Syndicate records, the BlueMap bridge and the TAB adapter. These checks do
not establish physical-client rendering or a load benchmark.

The separate integration fixture uses real, authenticated, non-OP Java protocol clients on loopback TCP 25601. Its Geyser endpoint is restricted to loopback UDP 25602. It verifies new administrative permissions, the old permission no longer authorizing creation, conversation revocation, creation for another leader, the tagged wand's actual left/right network packets, a copied wand in ordinary hands, actual actionbar packets, native block breaking/placing/chest access, existing creative claims and clan trust, lifecycle identity, and a clean restart.

Prepare it only from a disposable loopback WestShield four-world sample; never use a production data directory:

```sh
python3 westshield/territories/integration/prepare_fixture.py --source /path/to/isolated-four-world-sample --server-root /path/to/prepared-purpur-server
WestShield_JAVA_BIN=/path/to/java25/bin/java /path/to/python-with-nbtlib-and-pyyaml westshield/territories/integration/run.py
```

The fixture, credentials, player databases and raw logs are ignored by Git. `TerritoryProbe-test-only.jar` must never be installed on production. Retain a failed attempt before starting another; fixture errors are not passing product evidence. Physical Java/Bedrock GUI and touch/controller operation require a real client and are not established by Java protocol tests. The plugin uses standard actionbars and provides command-based point selection as an alternative to tool input.

Previous acceptance receipts remain unchanged under `docs/history/pre-westshield/territories`; they refer to their recorded builds. WestShield rebranding has a separate acceptance report and does not recast earlier results as new tests.
