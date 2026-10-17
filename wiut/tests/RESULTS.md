# WIUT verification — 17 October 2026

58 checks passed on the full isolated stack. Three non-OP Java protocol accounts were used. Physical Java/Bedrock GUI and voice remain unverified.

## Full-stack behavior

- WIUT SimpleClans fork loads on Purpur 26.2 with GriefPrevention 16.18.7
- AuthMe blocks clan creation before login
- Three ordinary non-OP players log in and enter creative
- Ordinary owner creates a creative claim
- Owner places a real block through the client protocol
- Owner breaks a real block through the client protocol
- Outsider cannot place a real block
- Outsider cannot break a real block
- Ordinary player creates a named clan with a short tag
- Invited player joins the clan
- New clan receives its live group permission immediately, without reload or reconnect
- Native GriefPrevention grants clan access across the full claim height
- Clan membership does not grant building in unclaimed creative land
- Clan member places a real block in the shared claim
- Clan member breaks a real block in the shared claim
- Clan member opens a real claimed chest
- Outsider cannot open the claimed chest
- Sharing with the clan does not admit an outsider
- Member leaves the clan
- Leaving the clan immediately removes native build access
- Rejoining restores clan access
- Leader excludes a member
- Exclusion immediately removes native break access
- Reconnect requires AuthMe again
- Clan membership does not allow block placement before AuthMe login
- Clan membership does not allow block breaking before AuthMe login
- Clan membership survives reconnect
- Clan building works while claim owner is offline
- Owner revokes shared clan access
- Coordinates, vitals, home teleport, bank and war permissions are disabled
- A second freshly created clan immediately receives its independent group permission
- Another clan cannot grant itself access to a foreign claim
- Owner can grant access to a specifically selected different clan
- A selected clan can receive container-only access
- Container-only clan permission does not grant building
- Clan identity is shared across worlds while claim protection remains creative-only
- Clan commands preserve survival inventory, Ender Chest and return location
- Creative inventory and location remain separate after clan operations
- Clan identity persists after a clean server restart
- Shared claim access persists after restart
- Clan permission reattaches after server restart
- Disbanding the clan removes membership and live group permissions
- Disbanding the clan removes native block access
- Supported locale API fixes the old ACF player-language exception

## Minimal player configuration

- Allowlisted create, invite and accept work without broad player permissions
- Native clan claim access works with the minimal command permissions
- Unused coordinate, vitals, home, regroup, bank and war permissions are absent
- Disabled feature commands cannot teleport the player
- Another real protocol client receives ordinary chat with the clan tag
- Native chat confirms the disband prompt and revokes group permission
- A disbanded clan loses claim access under the minimal configuration

## Separate mechanism permissions

- Mechanism checks use three authenticated non-OP players
- A clan member cannot activate a protected lever before permission is granted
- Mechanism-only clan trust permits a native lever interaction
- Mechanism-only clan trust does not grant block placement
- Mechanism-only clan trust does not grant chest access
- Mechanism-only trust does not admit another clan
- Revoking clan trust immediately removes lever access

## Build

Plugin SHA-256: `1495116c05da32669deebcae6bfa41076b848cdd70c06d71cb83dec8a14089fd`.

The original upstream behavior needed two compatibility fixes: public API locale reads and immediate permission attachments for new clans.
The broad land-all command also needs an explicit permission. The reviewed configuration uses native GriefPrevention group trust instead.

Early harness/confirmation attempts are excluded from the passing count. No production server or real player data was modified by these tests.

The additional seven mechanism checks used the identical JAR after transfer. The original 51 checks were retained, not rerun. A preliminary status-listener readiness attempt is excluded from the passing count.
