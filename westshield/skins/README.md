# Minecraft and Ely.by skins

WestShield uses the unmodified SkinsRestorer 15.12.6 release with WestShieldSkins 1.0.0 to keep the requested skin sources explicit. This is a skin integration, not Ely.by authentication. AuthMe, offline account UUIDs, whitelist, inventories, clan identities and existing bans are retained.

## Player commands

| Command | Behavior |
| --- | --- |
| `/skin <nickname>` | Copy the public skin of that **Minecraft account**, using Mojang's account and signed-profile endpoints. |
| `/skin set <nickname>` or `/skin minecraft <nickname>` | The same explicit Minecraft source. |
| `/elyskin <nickname>` or `/skin ely <nickname>` | Copy the public skin from **Ely.by**. |
| `/elyskin` | Use the Ely.by skin for your current Java nickname. |
| `/skin update` | Refresh the selected account skin while retaining its source identity. |
| `/skin clear` or `/skin reset` | Remove the manual selection and restore your own nickname's skin. |

All changes require AuthMe sign-in and affect only the requesting player. A 30-second change cooldown and bounded lookup concurrency protect the services. A failed lookup leaves the visible skin unchanged. Requests use public profile data; no Microsoft or Ely.by password, session token or paid API key is requested.

On Java join, SkinsRestorer checks Ely.by for the player's nickname, with its supported Mojang fallback. A manual selection persists across reconnects and restarts and takes priority over the automatic lookup. A skin choice does not rename the player or change their server UUID. An unrelated Ely.by account with the same name cannot override the explicit Minecraft command.

Floodgate's Bedrock skin is retained by default; the upstream hook skips automatic Java skin lookup for Floodgate accounts. The physical Bedrock client/controller path has not been acceptance-tested in this increment. Java players and their observers need no Ely.by client patch: Ely requests use the signed-texture endpoint and vanilla-compatible `textures.minecraft.net` images. Third-party launchers can independently override server skins on their own client.

Existing PvP trophies freeze the player's current profile. Native checks verify that an Ely skin and the victim name survive head creation, skull placement and block loot. WestShieldHeads does not need to be modified.

## Configuration and source routing

The minimal installed [SkinsRestorer configuration](SkinsRestorer-config.yml) enables `api.elyByEnabled`, disables random/default replacement skins, recommendations, unsolicited offline-launcher warnings and optional GUI/skull commands. Messages and help remain English regardless of the client locale. `plugins/SkinsRestorer/noautoupdate.txt` prevents an automatic binary replacement of the pinned release.

`WestShieldSkins` intercepts authenticated self-service Minecraft name/update commands and provides `/elyskin`. It resolves the requested provider asynchronously, caches the signed texture under the **provider's skin UUID**, then invokes SkinsRestorer as that same player. SkinsRestorer retains its own permission checks, cooldowns, history, durable selection storage and native Paper profile refresh. The server account UUID is never replaced with that skin UUID. The exact captured player connection, authentication and permission are rechecked before dispatch; a late lookup cannot apply to a replacement connection.

Only fixed HTTPS profile services are contacted. Nicknames are limited to Java username characters; requests have an eight-second timeout, response bodies are limited to 64 KiB, redirects are disabled, and the two-worker queue is bounded. Texture signatures must be present and images must use the vanilla texture host. The acceptance fixture also independently verifies an Ely texture signature against an official Mojang public profile key.

Upstream Ely-first configuration also affects its generic name lookups. The adapter therefore uses Mojang's own account/profile endpoints for `/skin <nickname>` instead of allowing a same-name Ely account to select the texture. Its `/skin update` path also avoids upstream's Ely-first fresh-profile lookup rejecting a Minecraft UUID before reaching Mojang. No upstream classes or binaries are patched.

The [permission changes](permissions.json) grant only the named self-service commands. `skinsrestorer.player` is explicitly false for the default group so the upstream default wildcard does not grant URL uploads or other extra commands. Updating other accounts and administrative commands remain privileged. `/westshieldskins status` is operator-only and exposes readiness/counters, not credentials or message bodies. Its `submitted` count records upstream command dispatches, not proof of a displayed skin.

Skin selections/cache are stored under `plugins/SkinsRestorer`; retain this directory and the auto-update sentinel in backups and upgrades. The new adapter has no separate player database. Rollback removes only the two added plugins and their data and reverses the six added permission nodes; it must preserve newer AuthMe, player, world and punishment history.

## Build and acceptance

The upstream JAR is pinned in [dependency.lock.json](dependency.lock.json). WestShieldSkins inherits this repository's GPL-3.0 license and uses the published SkinsRestorer API:

```sh
python3 westshield/english/build.py --server-root /path/to/server --dependency-directory /path/to/SkinsRestorer --only WestShieldSkins
```

[verification.json](verification.json) records the exact JAR hashes, isolated protocol checks and production readiness. Tests use public skin data and fictional local server accounts; they neither authenticate to those external accounts nor create players/punishments on production. Separate checks cover Minecraft/Ely source selection, signed properties received by another Java connection, AuthMe admission, permissions, cooldowns, restart persistence, native head loot and unchanged anti-cheat accounting. Successful protocol transfer is not graphical or physical Bedrock acceptance.

Primary references: [Ely.by signed textures](https://docs.ely.by/en/skins-system.html), [SkinsRestorer external services](https://skinsrestorer.net/docs/reference/services), [SkinsRestorer source at 15.12.6](https://github.com/SkinsRestorer/SkinsRestorer/tree/15.12.6).
