# Minimal WestShield configuration

1. Back up the server and existing SimpleClans/GriefPrevention data.
2. Install the WestShield SimpleClans JAR with the server stopped. Retain one active SimpleClans JAR.
3. Use `config/simpleclans.yml` as a reviewed configuration example. It enables immediate clan group permissions, keeps wars and built-in land sharing disabled, and disables automatic clan purges and charges.
4. Configure GriefPrevention only in the desired creative world. The tested WestShield world uses `SurvivalRequiringClaims` while Multiverse sets the actual player game mode to CREATIVE; this avoids GriefPrevention's automatic creative claim cleanup.
5. Apply the commands in `config/player-permissions.txt` from the console using LuckPerms. They allowlist basic clan management; coordinates, vitals, homes, regrouping, wars and bank commands are excluded.
6. Use `/trust [group.tag]` on an owned creative claim. Regular personal trust remains available.
7. Clan creation now requires `westshieldclans.admin`, defaulting to operators. For a deliberately delegated non-OP administrator, grant `lp user <name> permission set westshieldclans.admin true` from the server console. Do not grant this permission to the default player group. The old `simpleclans.leader.create` permission no longer authorizes creation by itself.
8. Install the separate [territory plugin](territories/README.md) if informational clan polygons are wanted. The same administrative permission controls its editing commands; AuthMe login is mandatory for player actions.

The example uses short ASCII clan tags and separate human-readable clan names. Floodgate player names retain their normal prefix, and Java/Bedrock accounts are distinct identities. No automatic account linking is introduced.

The test fixture keeps AuthMe mandatory. Ordinary players cannot create clans; all administrators must authenticate first. The claim owner may be offline; current clan membership still controls access.

The fork alone does not create worlds, configure Geyser, alter world borders, publish a web map or change survival inventories. Those remain the responsibility of the host's established plugins and configuration.
