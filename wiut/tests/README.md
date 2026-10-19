# Verification scope

The original scenarios and recorded production results below describe `wiut.1`, when ordinary clan creation was allowed. Since `wiut.2`, creation is restricted to administrators. Current geometry, permission, actionbar, gameplay and restart checks live under [territories](../territories/README.md); do not treat the old player-create assertions as acceptance of the new policy.

`results.json` records the full-stack scenario, supplemental minimal-permission scenario and separate mechanism-permission scenario. These checks used connected, non-OP, offline Java protocol accounts with AuthMe on a separate loopback-only Purpur 26.2 build 2633 / Java 25 server.

Actual network packets placed and broke blocks, opened chests and sent commands. An observer plugin inspected loaded clan/claim state; it never granted the test players OP. The tests cover fresh clan permissions, invitation, exit, exclusion, owner-offline access, pre-authentication restrictions, whole-height trust, selected foreign clans, container-only access, world transitions, clean restart and disbanding.

The isolated scenario source and observer plugin are supplied under `integration/`. They require a prepared, disposable four-world WIUT fixture with the full plugin stack. The private fixture, its generated accounts/databases and its worlds are not published. `run_scenario.py`, `run_minimal.py`, `run_access.py` and the observer plugin are not a fresh-server bootstrap and must never target a live server.

Set `WIUT_TEST_SERVER` to the prepared fixture and `WIUT_JAVA_BIN` to Java 25. Install the observer from the included source only in that disposable fixture. The server must bind 127.0.0.1:25597; all extra HTTP/UDP services must also bind loopback. Python dependencies: `nbtlib` and `PyYAML`.

Physical Java/Bedrock GUI, touch/controller shovel input, invited real Xbox identity admission and voice have not been checked by these scenarios. A server status query cannot establish those behaviors.

Earlier attempts found the upstream locale exception and missing fresh-clan group attachment. Separate early harness attempts needed a console permission, an observation-label fix and the plugin's leave confirmation command; they are not reported as passing product checks.

The additional mechanism scenario uses native lever packets to verify `/accesstrust [group.wiut]`, denial of block/chest access and immediate revocation. Its startup readiness guard waits on bounded read-only status observations before connecting players.

## Manual remote clients

The `Manual live protocol clients` workflow runs only by explicit dispatch. It is useful when the owner's network blocks game ports. It requires three temporary whitelisted, non-OP identities, a short-lived `WIUT_LIVE_TEST_PASSWORD` repository secret and an owner-controlled read-only HTTPS control document. The runner receives no panel credentials or console access and never installs plugins.

The control sequence is claimed before an ordinary client action. Missing acknowledgements require observation of the existing sequence, not a new action or replay. Host-side assertions and private console evidence remain with the owner. Remove the temporary whitelist entries, registration data, control document and secret after the diagnostic. The workflow does not enable a schedule or establish physical Java/Bedrock GUI coverage.

## Production acceptance

`production-results.json` records the separate 17 October 2026 installation acceptance: 23 native checks before restart and 13 after restart, plus final configuration/service observations. The identical published JAR was used. The test accounts had no OP, the test claim was removed, temporary registrations/player storage were cleaned up and the original invited players remained whitelisted. No observer plugin was installed on production.

Earlier transport/harness failures are retained privately and excluded from the passing count. The remote harness needed adjustments for panel timeouts, password length, console echo/ANSI parsing and existing connection/AuthMe delays. The accepted stages are distinct receipts; the interrupted reconnect was not reported as successful. Physical Java/Bedrock GUI and voice remain unverified.
