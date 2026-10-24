# WestShield SimpleClans

An unofficial, GPL-3.0 fork of [RoinujNosde/SimpleClans](https://github.com/RoinujNosde/SimpleClans), based on release 2.19.2 (`05e7abccbdd46339691b9a01eae84caae817b3ef`). The WestShield build targets **Purpur 26.2 build 2633 and Java 25**.

## Changes

- Read the player's locale through the supported Paper API, replacing old ACF reflection against a removed Minecraft field.
- Create clan permission attachments immediately for newly created clans. Clan group permissions no longer require a reload or reconnect.
- Provide a small configuration and player permission allowlist for clan names, membership and GriefPrevention trust.
- Use English for direct and interactive creation authority errors.
- Restrict new clan creation to `westshieldclans.admin` (operators by default), including permission revalidation after an interactive conversation.
- Keep deferred clan saves distinct by object identity and discard disbanded instances, so recreating a tag cannot restore the previous clan's data during a later save or shutdown.
- Provide the separate [WestShieldClanTerritories](westshield/territories/README.md) plugin for administrator-selected, concave polygons and entry/exit actionbars. Its territories do not protect land.

The clan data model and SQLite schema remain upstream SimpleClans. The deferred-save queue additionally validates the current clan incarnation. This is a targeted server fork, not an upstream release.

## Claim access

Enable `permissions.auto-group-groupname: true`. Members receive the live permission `group.<tag>`. Use GriefPrevention's normal trust commands while standing in an owned claim:

```text
/trust [group.westshield]
/containertrust [group.westshield]
/accesstrust [group.westshield]
/untrust [group.westshield]
/trustlist
```

This can target the owner's clan or a different selected clan. Leaving, exclusion and disbanding remove the clan permission; an independently granted personal trust remains independent.

The example disables SimpleClans land sharing (`war-and-protection.land-sharing: false`): GriefPrevention remains responsible for permission decisions. This avoids an additional plugin uncancelling block or inventory events. GriefPrevention can be enabled only in a creative world through its existing per-world configuration.

Clan trust is keyed by the tag. Recreating a clan under the same tag matches existing trust entries for that tag. Remove obsolete entries with `/untrust [group.tag]` when retiring a tag. This fork does not introduce immutable clan IDs or migrate existing claim identities.

## Configuration and verification

See [WestShield setup](westshield/SETUP.md), [English player guide](westshield/PLAYER_GUIDE_EN.md) and [test scope](westshield/tests/README.md).

The tested stack includes AuthMe, Geyser/Floodgate, Multiverse-Inventories, GriefPrevention and the existing WestShield creative-world transition plugin. Headless Java protocol checks do not establish physical Java/Bedrock GUI, mobile input or voice support. Recorded evidence identifies the tested versions and remaining checks.

## Build

The supported WestShield build is a deterministic class overlay over the pinned upstream distribution. Unchanged classes/resources retain the upstream implementation; changed source is included in this repository. The original Maven project is retained for upstream history, while the WestShield changes use the build below.

Requirements: Python 3, the tested OpenJDK 27 compiler with `--release 25`, and the `libraries` directory populated by Purpur 26.2 build 2633. Five API libraries are required; their exact paths and hashes are in `westshield/server-libraries.sha256.json`.

```sh
python3 westshield/build.py --server-libraries /path/to/purpur-26.2/libraries
```

The script downloads three public, hash-pinned compile dependencies when missing. Vault is a compile-only API dependency; the example runtime configuration does not require installing Vault.

Output: `westshield/SimpleClans-2.19.2-westshield.1.jar` and its SHA-256 file. Replace the old SimpleClans JAR while the server is stopped, retaining the plugin's data folder and a current backup. The optional territory plugin has its own build and artifact; see its README.

The inherited upstream publishing workflows are archived under `westshield/upstream-workflows`; they are not enabled for this fork.

## License and attribution

[GPL-3.0](LICENSE). Original authors and embedded dependency notices are retained. See [upstream README](README_UPSTREAM.md) for the original project and documentation.

The current English server presentation and adapter build instructions are in [westshield/english/README.md](westshield/english/README.md), with an [English player guide](westshield/PLAYER_GUIDE_EN.md).

Current server identities and scoped verification are documented in [WestShield rebranding](westshield/REBRANDING.md) and [the exact-build acceptance report](westshield/rebranding/verification.json).

The installed SMP-style server list, original shield and real 200-player admission configuration are documented in [server presentation](westshield/presentation/README.md).

The authenticated, private SMP greeting and on-demand map/voice commands are documented in [welcome presentation](westshield/welcome/README.md).
