# Verification scope

`results.json` records the full-stack scenario, supplemental minimal-permission scenario and separate mechanism-permission scenario. These checks used connected, non-OP, offline Java protocol accounts with AuthMe on a separate loopback-only Purpur 26.2 build 2633 / Java 25 server.

Actual network packets placed and broke blocks, opened chests and sent commands. An observer plugin inspected loaded clan/claim state; it never granted the test players OP. The tests cover fresh clan permissions, invitation, exit, exclusion, owner-offline access, pre-authentication restrictions, whole-height trust, selected foreign clans, container-only access, world transitions, clean restart and disbanding.

The scenario source and observer plugin are supplied under `integration/`. They require a prepared, disposable four-world WIUT fixture with the full plugin stack. The private fixture, its generated accounts/databases and its worlds are not published. The scripts are not a fresh-server bootstrap and must never target a live server.

Set `WIUT_TEST_SERVER` to the prepared fixture and `WIUT_JAVA_BIN` to Java 25. Install the observer from the included source only in that disposable fixture. The server must bind 127.0.0.1:25597; all extra HTTP/UDP services must also bind loopback. Python dependencies: `nbtlib` and `PyYAML`.

Physical Java/Bedrock GUI, touch/controller shovel input, invited real Xbox identity admission and voice have not been checked by these scenarios. A server status query cannot establish those behaviors.

Earlier attempts found the upstream locale exception and missing fresh-clan group attachment. Separate early harness attempts needed a console permission, an observation-label fix and the plugin's leave confirmation command; they are not reported as passing product checks.

The additional mechanism scenario uses native lever packets to verify `/accesstrust [group.wiut]`, denial of block/chest access and immediate revocation. Its startup readiness guard waits on bounded read-only status observations before connecting players.
