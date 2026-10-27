# Isolated shared-profile probe

`AutoProbe.java` is the console-only test helper used by the local loopback
acceptance run. Never install it on a player server. The fixture contains only
synthetic accounts and uses separate loopback ports and a private fixture Floodgate key.

The `hostname` operation encrypts a complete native Floodgate `BedrockData` payload
with that fixture's own cipher. A Java-protocol client can then exercise the real
Floodgate handshake and player-profile loading path. This does not emulate Xbox
authentication, Bedrock packets, controllers, mobile UI or microphone behavior.

The `inspect` operation observes AuthMe authentication, effective UUID/name,
edition detection, inventory, XP and saved location. It never forces a login.
The harness verifies wrong-password denial, both registration orders, clean
restart and canonical bans. It also renames a synthetic legacy registration while
the fixture is stopped and verifies the old password hash is unchanged.

See the unchanged sixteen-check receipt in `../verification.json`. Production
verification is recorded separately and never introduces a probe or bot account
on the live server. Raw fixture and production logs are kept outside the repository.

The separate [legacy Bedrock receipt](../legacy-bedrock-verification.json) adds four checks for an unchanged native Floodgate UUID, mandatory authentication and the return to Java with preserved items. There are twenty isolated checks across the two receipts.
