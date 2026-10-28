# WestShield chat and game protection

WestShieldProtection 1.0.1 combines a censor-only language policy with one durable sanction policy for the pinned anti-cheat providers. The server remains Purpur 26.2 build 2633 / Java 25, with AuthMe, Geyser/Floodgate, creative inventory isolation, informational clan territories, render distance 16 and simulation distance 4.

## Chat

The bundled literal dictionaries cover 28 language lists: the 27 LDNOOBW lists plus WestShield's Uzbek Latin/Cyrillic supplement and Russian inflections/transliterations. This is broad dictionary coverage, **not a guarantee of every language, context or future spelling**. Neutral identity/anatomy words and ambiguous short Latin entries are excluded. The configured `extra-words` and `allow-words` let operators correct vocabulary without changing the code.

Matched spans become exactly `****`. There are no profanity warnings, mutes, kicks, bans or sanction-counter increments. Ordinary chat retains its formatting and audience. Filtered public messages are delivered as fresh components so an original signed-message hover cannot expose the censored content. SimpleClans clan/alliance chat and `/msg`, `/tell`, `/w`, `/me` and player `/say` text are also censored; target names are preserved. Authentication and voice passwords are never processed. Standard server command logging can still record original commands; this plugin does not log message bodies or credentials.

Normalization handles case, common Unicode lookalikes, Latin diacritics, leetspeak, separators, zero-width separators and repeated letters. Source spans and word boundaries prevent examples such as `class`, `assassin`, `Scunthorpe`, `analytics`, English `I am`, and Uzbek `qo'yaman`/`tashlayman` from being replaced as profanity. CJK/Thai dictionaries also work in text without spaces. Non-European combining marks are retained. Literal trie matching does not execute dictionary entries as regex.

Dictionaries are bundled; there are no runtime dictionary/model requests and no chat sent to an external moderation service. [Dictionary provenance](dictionaries.lock.json) pins upstream files and hashes. The base data is CC BY 4.0; its license and attribution are included in the JAR. The derived vocabulary supplements/removals are documented there. Clan/alliance channels remain disabled in the existing production configuration; they are enabled only in the isolated acceptance fixture to exercise their future censor hook.

### Owner-supplied Steam dictionaries

The installed 1.0.1 configuration also enables external dictionaries imported from the owner's installed Steam client. The snapshot contains 29 language/locale sets, 13,792 unique literal entries and 357 spelling patterns. The existing Uzbek Latin/Cyrillic supplement remains enabled because Steam does not supply Uzbek. Additional Russian inflections, transliterations and insults reported by the owner are included in the bundled server vocabulary.

The importer examines 819,908 Steam clean-list entries and retains 675 relevant exceptions, including the server's neutral-word policy. It does not import legal/political dictionaries. Matching examples include the reported insults, inflected forms, mixed transliteration, repeated letters and separated spellings. Ordinary phrases such as `иди домой`, `такое бывает`, `плохой интернет`, `лохматый кот` and `I am from Tashkent` remain unchanged in acceptance tests.

The literal entries extend the existing Unicode trie. Spelling patterns use the pinned, embedded [RE2/J 1.8 runtime](re2j.lock.json), whose BSD license is included in the JAR. RE2/J avoids exponential backtracking; its Unicode word classes are adapted to include Cyrillic. External file sizes, pattern counts and SHA-256 checksums are validated before enabling the filter. This is an adaptation of owner-supplied dictionaries, **not the Steamworks filtering API**: Steam friendship/preferences behavior and identical Valve matching are not claimed. Unknown future variants remain possible.

Valve dictionary contents are kept under private `plugins/WestShieldProtection/steam/` configuration. They are not embedded in the JAR, source repository or public release. There is no Steam installation, Steam account session or remote moderation request on the game server. Reproduce an owner-authorized import outside the repository with:

```sh
python3 westshield/protection/import_steam.py --steam-resource /path/to/Steam/resource --output /private/path/steam-dictionaries
```

Install the generated four files under `plugins/WestShieldProtection/steam/` and set `external-steam-dictionaries: true` in the existing configuration before restarting. Preserve `strikes.yml` and existing `extra-words`/`allow-words`. A missing or changed enabled dictionary refuses startup rather than silently discarding its rules. The public JAR defaults to external dictionaries disabled; the installed server enables its verified private snapshot.

The current [Steam increment verification](steam-verification.json) records actual dictionary hashes/counts, tests and live readiness. The original [1.0.0 verification](verification.json) remains historical, including its original artifact hashes and qualifications. Neither increment claims a physical Bedrock acceptance session or universal spelling coverage.

## Anti-cheat providers

- **GrimAC 2.3.74** checks Java clients. Floodgate remains installed on the backend so Grim's supported Bedrock exemption works.
- **LightAntiCheat+ 2.0.2** is configured globally for **Bedrock only**, with per-check Java detection disabled. Floodgate API identification is authoritative; name-prefix and UUID-prefix guesses are disabled. There are no overlapping Java movement engines.
- Experimental Grim checks and speculative aim/autoclicker sanctions are excluded. Thresholds require repeated violations, rather than turning an isolated flag into a permanent ban. LAC uses at least 18 TPS, prioritizes accuracy, and has join/teleport grace. The adapter independently gates authentication, current connection identity and short travel/respawn/mode-change grace.
- Existing creative flight and GSit policies remain owned by their plugins. LightAntiCheat documents GSit/Geyser compatibility. A complete physical Bedrock movement/controller acceptance session has **not** been performed by these native Java tests.

Exact upstream artifacts, supported game versions, publisher SHA-512 and local SHA-256 are in [dependencies.lock.json](dependencies.lock.json). These are unmodified upstream JARs, not renamed custom builds. Neither publisher's update check installs a new binary automatically.

### Bedrock packet compatibility correction (2026-10-27)

`BadPacketsC` is disabled in the current Bedrock-only configuration. Its installed
packet mapper classifies both `PLAYER_INPUT` and `STEER_VEHICLE` as vehicle input,
while the check flags moving players outside a vehicle. A physical Bedrock session
produced repeated `BadPacketsC` violations and a confirmed kick at 20 TPS. This
check therefore cannot provide reliable automatic punishment on this server.
Its detection and punishment switches are also off; other check settings, Grim's
Java detection and the three-confirmed-kick policy remain unchanged.

The single identified false-positive receipt was voided after a clean stop, with
its original ledger and configuration retained in private backups. No unrelated
account history or native ban was removed. Runtime verification checks that the
provider lists `BadPacketsC` as disabled and the affected account has zero strikes.
A new physical Bedrock movement session is still required; this configuration
correction is not evidence that every remaining check is free of false positives.

## Confirmed kicks and permanent bans

Grim's current event bus and LAC's punishment event feed one adapter. Stock independent punishment commands are disabled; the adapter binds each decision to the captured account/connection and refuses stale or unauthenticated decisions. Multiple flags/checks in one connection cannot become multiple kicks.

A counter increments only after the adapter observes its accepted anti-cheat kick **and the corresponding actual quit**. Paper can queue disconnection; cancelled kicks and unconfirmed attempts do not count. The policy is:

1. First confirmed anti-cheat kick: disconnect; counter 1.
2. Second confirmed anti-cheat kick: disconnect; counter 2.
3. Third confirmed anti-cheat kick: disconnect and a permanent native profile ban.

Counters use account UUIDs, survive restarts, and have atomic file replacement with idempotent receipt IDs. Authentication failures, normal network exits, manual kicks and profanity do not count. This is an account ban, not an IP ban; Java and Bedrock accounts have distinct UUIDs unless linked by their existing account system.

`plugins/WestShieldProtection/strikes.yml` is private operational state and is included by normal plugin backups. Do not delete it during routine upgrades. A saved count of three also refuses login, including an interrupted native-ban write. The installed 1.0.1 runtime has no command to reset its in-memory counter: merely removing a native ban does not clear the second admission block. Do not edit a running server's ledger or interrupt players to apply a pardon without explicit operator approval.

Operators can use `/westshieldprotection status` and `/westshieldprotection history <uuid>`. These require `westshieldprotection.admin` (operator by default) and player authentication. There is no player-accessible strike command or default bypass.

## Version 1.0.2: administrator pardons

Version 1.0.2 adds `/wsunban <name|uuid> [reason]` and
`/westshieldprotection pardon <name|uuid> [reason]`. Both require
`westshieldprotection.admin` (operator by default), plus AuthMe authentication for
player senders. They operate on the canonical account UUID used by the ledger and
native profile bans, without IP bans, teleports, inventory changes or restarts.
Ambiguous names are refused; an explicit UUID can select the intended account.

The command atomically saves a ledger audit/reset before publishing zero active
strikes to login threads. It then removes the native profile ban and verifies its
absence. These are two storage systems, not a shared transaction: a failed ledger
save leaves the ban unchanged; a later native-pardon failure reports that the
counter was cleared and asks the administrator to retry the command. Server logs
record the final native result against the durable pardon action ID.

Original receipts, actor and reason remain under `pardons` in the same version-1
ledger. Archived receipt IDs cannot be counted again. Three fresh confirmed kicks
can start a new permanent ban. Repeating an already completed pardon is a no-op.
Pending accounting for a pardoned connection is fenced, without claiming that an
already-issued disconnect can be cancelled.

Version 1.0.2 compiles against the pinned Purpur 26.2 runtime and passed 67
dictionary/persistence checks, including 12 pardon checks. It was installed on
2026-10-27 during an explicitly approved restart. A real administrator pardon was
observed completing with the native ban removed and the active count reset to zero.
Future pardons require no restart. No player teleport commands were used.

The current compatibility policy logs timing, simulation, packet-order and similar
checks without automatic sanctions. Reach and hitbox thresholds are 20 and 30,
with a 60-second violation window. Critical exploit checks retain sanctions.
Bedrock LAC checks remain enabled for observation with automatic punishment off.
Anti-xray remains unchanged.

## Anti-xray and exploitation

Paper anti-xray **engine mode 3** is enabled per world in Survival and the Nether. The Nether list includes ancient debris; the Overworld list includes both ordinary and deepslate ores. The update radius is 2 and permission bypass is disabled. Air is deliberately not replaced with fake blocks, avoiding the documented client FPS cost. Creative and the closed End remain unobfuscated. Only outgoing block data changes; existing blocks, inventories and world seeds are retained.

Packet, reach, interaction and exploit checks are provided by the anti-cheats and the existing server. Anti-xray itself is obfuscation, not proof that a player installed an x-ray client; it does not generate automatic mining accusations or kick receipts. `/seed` remains available as previously requested. **A known world seed can bypass ore obfuscation through seed-based ore prediction**, as [Paper documents](https://docs.papermc.io/paper/anti-xray/). This installation does not regenerate ores or pretend to revoke knowledge of the existing seed.

## Build and acceptance

```sh
python3 westshield/english/build.py --server-root /path/to/server --dependency-directory /path/to/pinned-anticheats --embed-re2j /path/to/re2j-1.8.jar --only WestShieldProtection
```

The pinned dependency directory must contain GrimAC 2.3.74 and LightAntiCheat+ 2.0.2. The exact installed AuthMe/Floodgate/SimpleClans/Paper libraries come from the supplied server root. The build embeds `resources/words.txt` and its notices. Source is in `english/adapters/protection` and inherits the repository's GPL-3.0 license; upstream providers retain their own notices/licenses.

Run the 55 dictionary and persistence checks with Java/Javac 25 or newer:

```sh
python3 westshield/protection/tests/run.py --server-root /path/to/server
python3 westshield/protection/tests/run.py --server-root /path/to/server --steam-directory /private/path/steam-dictionaries
```

The [configuration examples](config-example/) contain the installed provider settings and only the anti-xray sections of world settings. Merge each world section into that world's `paper-world.yml`; do not replace unrelated world settings.

The historical [1.0.0 verification](verification.json) records its original artifacts and scope. Unit checks cover vocabulary/false-positive examples, Unicode handling, counter replay/reload/threshold and failed/malformed persistence. Its isolated full-stack Java protocol tests cover visible chat/private messages, ordinary/manual/cancelled kick exclusions, real provider thresholds, three confirmed disconnects, native permanent bans and restart persistence. A separate network test sends actual impossible rotation packets through Grim and verifies a single confirmed kick; the banned account is also refused after a further restart. The current [1.0.1 Steam verification](steam-verification.json) adds 51 dictionary/pattern/integrity checks and 26 native chat/restart checks, alongside the original 55 unit checks. Test observers and accounts are never deployed on production. Synthetic provider flag injection checks the punishment pipeline, not independent detector accuracy; physical Bedrock play and 200-player load capacity are not established.

## Version 1.0.3: local and global public chat

Ordinary messages retain the existing renderer and reach authenticated, visible
players within 100 blocks in the same dimension. Distance is checked in 3D on the
main thread at delivery, including the exact 100-block boundary. A leading `!`
selects global delivery across worlds and adds a gold `[Global]` label; the prefix
is removed from the body. Empty global messages are ignored. Console observation
remains available to the host.

Both channels share the existing profanity filter and AuthMe/current-session
checks. Censored local messages cannot escape their local audience; no automatic
profanity sanctions are added. Other event cancellations and audience restrictions
are respected. Configure `local-chat-radius` (default 100, bounds 1–1000).

The candidate passed 76 unit checks and eight native loopback chat checks,
including boundary/dimension delivery and censoring in both channels. It was activated on 2026-10-28 during an explicitly approved clean restart.
The installed artifact hash matches the native fixture; live status confirms
the 100-block local radius and `!` global prefix. AuthMe no-teleport settings and
the sanction ledger were preserved. No forced player teleports were performed.
