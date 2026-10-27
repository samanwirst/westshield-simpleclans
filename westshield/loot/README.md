# WestShield personal natural loot

WestShieldLoot 1.0.0 is an original server-side plugin for the pinned Purpur 26.2 /
Java 25 server. It requires AuthMe and WestShieldAccounts. No player mod or resource
pack is required. Java and Floodgate players use the effective Bukkit UUID, so a
shared account has the same permanent roll on either edition.

## Player behavior

- One independently randomized vanilla roll per account and natural container.
  An established player can still discover a different container for the first
  time. Different players' individual items or entire rolls can coincide.
- Reopening shows the original remaining loot. There is no timer, refresh, reroll,
  expiry, inactivity purge or player-accessible reset command.
- Chests, double chests, barrels and chest minecarts are supported. The normal
  Overworld and Nether are enabled by dimension key. Creative and the End are
  excluded; Survival/Adventure authentication is checked on opening and taking.
- Trial Vaults, their normal/ominous keys and their native rewards remain vanilla.
  Dispensers, archaeology, item frames, mobs and player storage are not converted.
- The inventory is take-only. Left-click/shift-click transfers a stack directly
  into free player inventory space; right-click takes one item. Dragging, deposits,
  hotbar swaps, cursor storage and automatic hopper transfers are denied. Nothing
  spills on the ground when the player inventory is full.
- Natural containers may be broken or exploded. Destruction retires the entire
  registered container, including both halves of a double chest and every player's
  unclaimed contents. Previously received items stay with their owners. A new
  player chest at the same position is ordinary and does not recreate the reward.
  There is no automatic restoration. Existing building/claim permissions still apply.
- To extend a registered single chest, break it first, then place normal chests.
  This avoids mixing a personal loot inventory with a player's ordinary warehouse.

## Natural-container admission

Existing unfilled containers with a recognized vanilla `minecraft:chests/...`
loot table are eligible, including pregenerated chunks. The bonus chest is excluded.
Placement events mark player-created tile entities/minecarts and strip copied
plugin markers. Containers without original loot information are never guessed
from their position or filled with invented replacement loot. Already-opened,
ordinary chests remain ordinary. Deliberate NBT creation by a trusted administrator
can imitate a natural loot table; the plugin does not claim forensic provenance
against an operator who can rewrite world data.

An inventory-generation guard intercepts unclaimed natural loot reached by
hoppers before first human access and preserves the original table in block/entity
metadata. The player interface is opened only after durable registration. Existing
AuthMe/claim event denials are never removed. Container identity and tombstones
are permanent; coordinates are not a mechanism for respawning a broken chest.

## Persistence and recovery

`plugins/WestShieldLoot/loot.sqlite` owns container provenance, reserved roll seeds,
remaining contents and pending deliveries. SQLite uses FULL synchronous commits,
foreign keys, transactions and a single database worker. Player item blobs are
loaded per request; all players' inventories are not retained in a global cache.
Requests are bounded per player and registration. Metadata has a configurable hard
ceiling. Roll reservations survive interruption before generation.

Every take durably debits the personal loot and journals exact before/after player
and loot snapshots before applying items. The native player save precedes journal
acknowledgement. After restart, an exact native-after match acknowledges delivery;
an exact native-before match returns the original debit to that same roll. Unknown
state or another world remains held for operator review, without another grant.
SQLite and Minecraft do not share an atomic transaction: this is conservative
recovery, not a claim that arbitrary crashes or unrelated-plugin mutations can
always be repaired automatically. Failed persistence pauses loot and retains records.

Back up the whole plugin directory and worlds together. A consistent full restore
requires a clean stop. Do not restore an older loot database over newer player
inventories or delete the database to fix an incident: that can remove consumption
history. CoreProtect still owns normal block history; its ordinary block rollback
is not a rollback of this virtual loot database. The original block's loot table
is cleared after durable registration, so removing the plugin does not put the
remaining personal contents into physical chests. Preserve its data for reinstallation.

## Administration and build

`/westshieldloot status` and `/westshieldloot inspect` require
`westshieldloot.admin` (operator by default) and AuthMe authentication for players.
The status exposes counts only; inspect identifies the targeted registered block.
There is deliberately no reward reset, restore, refill or arbitrary import command.

```sh
python3 westshield/english/build.py --server-root /path/to/server \
  --dependency-directory /path/to/current-accounts-and-authme-jars --only WestShieldLoot
```

SQLite JDBC 3.49.1.0 is an explicit pinned plugin library, resolved through Paper's
library loader. The JAR contains original WestShield code and uses the repository's
GPL-3.0 license. The SQLite driver retains its upstream license. No other loot
plugin code is incorporated or installed alongside this plugin.

`tests/StoreChecks.java` exercises durable seed/claim behavior, transactional debit,
crash-boundary recovery, unknown-state holds and permanent destruction. The
loopback harness in `integration/` consumes only an explicitly confirmed synthetic
server template. Its console-only probe encodes real Minecraft click/use packets
with the pinned server codec; it is never a production plugin. An encrypted native
Floodgate handshake exercises common account identity, not a physical Bedrock UI.
Synthetic Java actors have a fixture-only Grim movement exemption because the
minimal protocol client does not simulate full vanilla movement. Production
anti-cheat permissions and checks are unchanged. Additional minecart checks use
Bukkit interaction/destruction events; chest taking uses real protocol clicks.

The exact release artifact passed 29 storage checks, 23 native loopback checks
and 12 additional integration checks (64 total). Machine-readable receipts are
in `verification/`. Physical Bedrock touch/controller rendering still requires
a real-client check. Natural block lids use the native Lidded API.

Production installation requires a clean restart and a verified pre-install
world/plugin backup. Keep synthetic probes, test worlds and fixture permissions
out of production. The release adds only WestShieldLoot; whitelist, currency,
combat and authentication configuration are preserved. Existing Backuper jobs
include the new plugin data through their `plugins` directory inclusion.

Players do not need a command: open an eligible natural container, then click a
reward to take it. Staff can check `/westshieldloot status` from the console.
A previously emptied ordinary chest without its original loot table is not
retroactively reconstructed.

## Release status

Installed on WestShield on 2026-10-27 after a fresh world/plugin backup and a
clean restart. Production status reports ready, zero failures and no unresolved
transfers; the new SQLite database passes integrity checking. Server TPS is 20.
Java status and external Bedrock public status are available. Live verification
covered startup/status/integrity, while interactive loot acceptance was local.
No test probe, test account or anti-cheat exemption was installed in production.
See `verification/production.json` for the exact scope and artifact hash.
