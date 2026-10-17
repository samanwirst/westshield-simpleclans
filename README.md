# WIUT SimpleClans

An unofficial, GPL-3.0 fork of [RoinujNosde/SimpleClans](https://github.com/RoinujNosde/SimpleClans), based on release 2.19.2 (`05e7abccbdd46339691b9a01eae84caae817b3ef`). The WIUT build targets **Purpur 26.2 build 2633 and Java 25**.

## Changes

- Read the player's locale through the supported Paper API, replacing old ACF reflection against a removed Minecraft field.
- Create clan permission attachments immediately for newly created clans. Clan group permissions no longer require a reload or reconnect.
- Provide a small configuration and player permission allowlist for clan names, membership and GriefPrevention trust.

The clan data model and existing SQLite persistence remain upstream SimpleClans. This is a targeted compatibility fork, not an upstream release.

## Claim access

Enable `permissions.auto-group-groupname: true`. Members receive the live permission `group.<tag>`. Use GriefPrevention's normal trust commands while standing in an owned claim:

```text
/trust [group.wiut]
/containertrust [group.wiut]
/accesstrust [group.wiut]
/untrust [group.wiut]
/trustlist
```

This can target the owner's clan or a different selected clan. Leaving, exclusion and disbanding remove the clan permission; an independently granted personal trust remains independent.

The example disables SimpleClans land sharing (`war-and-protection.land-sharing: false`): GriefPrevention remains responsible for permission decisions. This avoids an additional plugin uncancelling block or inventory events. GriefPrevention can be enabled only in a creative world through its existing per-world configuration.

Clan trust is keyed by the tag. Recreating a clan under the same tag matches existing trust entries for that tag. Remove obsolete entries with `/untrust [group.tag]` when retiring a tag. This fork does not introduce immutable clan IDs or migrate existing claim identities.

## Configuration and verification

See [WIUT setup](wiut/SETUP.md), [player guide](wiut/PLAYER_GUIDE_RU.md) and [test scope](wiut/tests/README.md).

The tested stack includes AuthMe, Geyser/Floodgate, Multiverse-Inventories, GriefPrevention and the existing WIUT creative-world transition plugin. Headless Java protocol checks do not establish physical Java/Bedrock GUI, mobile input or voice support. Recorded evidence identifies the tested versions and remaining checks.

## Build

The supported WIUT build is a deterministic class overlay over the pinned upstream distribution. Unchanged classes/resources retain the upstream implementation; changed source is included in this repository. The original Maven project is retained for upstream history, while the WIUT changes use the build below.

Requirements: Python 3, the tested OpenJDK 27 compiler with `--release 25`, and the `libraries` directory populated by Purpur 26.2 build 2633. Only four API libraries are required; their exact paths and hashes are in `wiut/server-libraries.sha256.json`.

```sh
python3 wiut/build.py --server-libraries /path/to/purpur-26.2/libraries
```

The script downloads three public, hash-pinned compile dependencies when missing. Vault is a compile-only API dependency; the example runtime configuration does not require installing Vault.

Output: `wiut/SimpleClans-2.19.2-wiut.1.jar` and its SHA-256 file. Replace the old SimpleClans JAR while the server is stopped, retaining the plugin's data folder and a current backup.

The inherited upstream publishing workflows are archived under `wiut/upstream-workflows`; they are not enabled for this fork.

## License and attribution

[GPL-3.0](LICENSE). Original authors and embedded dependency notices are retained. See [upstream README](README_UPSTREAM.md) for the original project and documentation.
