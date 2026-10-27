# WestShield shared Java and Bedrock profiles

This adapter resolves the player profile before Purpur loads game data. A verified
Floodgate login with the same Java-compatible name uses the server's canonical
AuthMe name and UUID. Players register once and use `/login <password>` on either
edition. Matching a name never authenticates the player: AuthMe remains responsible
for passwords, registration, inventory locking, optional 2FA and single sessions.
There is no forced-login call and no password or token storage in this adapter.

The common UUID is also the key used by inventories, Ender Chests, advancements,
world-group inventory snapshots, clan memberships, claims, permissions, playtime and
anti-cheat history. This resolves identity rather than copying live inventories
between two competing player profiles. A player must disconnect from one edition
before connecting from the other.

## Name and invite rules

- Exact ASCII Java-compatible names (`[A-Za-z0-9_]{3,16}`) are eligible. Registered
  AuthMe casing wins. Spaces, visually similar characters and spelling differences
  are not silently merged. `Example1` and `Examplei` remain different accounts.
- A raw Floodgate identity or its canonical shared profile must already be
  whitelisted. Standard whitelist and ban checks still apply. After successful
  AuthMe registration/login, a Bedrock-only invite also whitelists the canonical
  UUID for future Java access.
- Revoke a shared invitation by removing the canonical whitelist profile and any
  remaining raw Bedrock invite for that person. Removing only an old alias does
  not revoke a separate canonical invitation.
- Native explicitly confirmed Floodgate links are preserved. They can be used for
  different gamertags. Global linking stays disabled on the AuthMe offline server.
- Same-name resolution is per connection. An observed nickname does not create a
  permanent native linking-database row or bypass the server password.

## Existing profiles

Back up the stopped server before migrating registrations. Existing Java profiles
normally keep their offline UUID and their existing AuthMe password. Never
concatenate inventories or overwrite newer player files to combine two old saves.
If both editions already have separate progress, preserve the secondary profile
for an explicitly chosen recovery rather than adding items automatically.

For a Bedrock-only legacy profile, an operator can preserve its existing UUID by
adding a private `profile-overrides` entry and renaming its prefixed AuthMe
registration to the canonical unprefixed name while the server is stopped. Keep
the password hash and other account fields unchanged and clear old session flags.
Also update the canonical whitelist name and the stopped server's name cache to
this retained UUID; remove stale duplicate cache aliases so name-based claim/admin
commands cannot select a discarded offline identity. This keeps every existing
UUID-based plugin record intact. The same override also
applies to Java pre-login, before ban/strike checks. Overrides must remain in all
future installs and backups. Never publish live UUID mappings or authentication
storage in the repository.

## Compatibility and administration

Pinned runtime: Purpur 26.2, Java 25, AuthMe 6.0.1-b2770 and Floodgate
`2.2.5-SNAPSHOT (b141-81b65cc)`. This uses Floodgate's public but deprecated handshake
extension API. A changed Floodgate build needs a new isolated compatibility test;
a failed identity adapter refuses new logins rather than silently splitting saves.
The Java pre-login profile setter is also pinned to this Paper/Purpur generation.

`/westshieldaccounts` is operator-only and reports readiness, resolution count and
errors. It does not reveal account secrets. Preserve the adapter's config, AuthMe
storage, whitelist, Floodgate local linking database and normal game/plugin data
in the existing private backup system. Keep Floodgate keys and backup OAuth tokens
out of public artifacts.

Local acceptance must exercise native encrypted Floodgate handshakes, mandatory
AuthMe login, wrong-password denial, both first-registration orders, legacy UUID
and password preservation, clean restart, shared game state and canonical bans.
An encrypted Java-protocol fixture is not a physical Bedrock client test.

Upstream references:
[local linking](https://geysermc.org/wiki/floodgate/linking/),
[handshake API](https://github.com/GeyserMC/Floodgate/blob/master/api/src/main/java/org/geysermc/floodgate/api/handshake/HandshakeData.java),
[AuthMe](https://github.com/AuthMe/AuthMeReloaded/wiki).
