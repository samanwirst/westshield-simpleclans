# WestShield chat and game protection

WestShieldProtection 1.0.0 combines a censor-only language policy with one durable sanction policy for the pinned anti-cheat providers. The server remains Purpur 26.2 build 2633 / Java 25, with AuthMe, Geyser/Floodgate, creative inventory isolation, informational clan territories, render distance 16 and simulation distance 4.

## Chat

The bundled literal dictionaries cover 28 language lists: the 27 LDNOOBW lists plus WestShield's Uzbek Latin/Cyrillic supplement and Russian inflections/transliterations. This is broad dictionary coverage, **not a guarantee of every language, context or future spelling**. Neutral identity/anatomy words and ambiguous short Latin entries are excluded. The configured `extra-words` and `allow-words` let operators correct vocabulary without changing the code.

Matched spans become exactly `****`. There are no profanity warnings, mutes, kicks, bans or sanction-counter increments. Ordinary chat retains its formatting and audience. Filtered public messages are delivered as fresh components so an original signed-message hover cannot expose the censored content. SimpleClans clan/alliance chat and `/msg`, `/tell`, `/w`, `/me` and player `/say` text are also censored; target names are preserved. Authentication and voice passwords are never processed. Standard server command logging can still record original commands; this plugin does not log message bodies or credentials.

Normalization handles case, common Unicode lookalikes, Latin diacritics, leetspeak, separators, zero-width separators and repeated letters. Source spans and word boundaries prevent examples such as `class`, `assassin`, `Scunthorpe`, `analytics`, English `I am`, and Uzbek `qo'yaman`/`tashlayman` from being replaced as profanity. CJK/Thai dictionaries also work in text without spaces. Non-European combining marks are retained. Literal trie matching does not execute dictionary entries as regex.

Dictionaries are bundled; there are no runtime dictionary/model requests and no chat sent to an external moderation service. [Dictionary provenance](dictionaries.lock.json) pins upstream files and hashes. The base data is CC BY 4.0; its license and attribution are included in the JAR. The derived vocabulary supplements/removals are documented there. Clan/alliance channels remain disabled in the existing production configuration; they are enabled only in the isolated acceptance fixture to exercise their future censor hook.

## Anti-cheat providers

- **GrimAC 2.3.74** checks Java clients. Floodgate remains installed on the backend so Grim's supported Bedrock exemption works.
- **LightAntiCheat+ 2.0.2** is configured globally for **Bedrock only**, with per-check Java detection disabled. Floodgate API identification is authoritative; name-prefix and UUID-prefix guesses are disabled. There are no overlapping Java movement engines.
- Experimental Grim checks and speculative aim/autoclicker sanctions are excluded. Thresholds require repeated violations, rather than turning an isolated flag into a permanent ban. LAC uses at least 18 TPS, prioritizes accuracy, and has join/teleport grace. The adapter independently gates authentication, current connection identity and short travel/respawn/mode-change grace.
- Existing creative flight and GSit policies remain owned by their plugins. LightAntiCheat documents GSit/Geyser compatibility. A complete physical Bedrock movement/controller acceptance session has **not** been performed by these native Java tests.

Exact upstream artifacts, supported game versions, publisher SHA-512 and local SHA-256 are in [dependencies.lock.json](dependencies.lock.json). These are unmodified upstream JARs, not renamed custom builds. Neither publisher's update check installs a new binary automatically.

## Confirmed kicks and permanent bans

Grim's current event bus and LAC's punishment event feed one adapter. Stock independent punishment commands are disabled; the adapter binds each decision to the captured account/connection and refuses stale or unauthenticated decisions. Multiple flags/checks in one connection cannot become multiple kicks.

A counter increments only after the adapter observes its accepted anti-cheat kick **and the corresponding actual quit**. Paper can queue disconnection; cancelled kicks and unconfirmed attempts do not count. The policy is:

1. First confirmed anti-cheat kick: disconnect; counter 1.
2. Second confirmed anti-cheat kick: disconnect; counter 2.
3. Third confirmed anti-cheat kick: disconnect and a permanent native profile ban.

Counters use account UUIDs, survive restarts, and have atomic file replacement with idempotent receipt IDs. Authentication failures, normal network exits, manual kicks and profanity do not count. This is an account ban, not an IP ban; Java and Bedrock accounts have distinct UUIDs unless linked by their existing account system.

`plugins/WestShieldProtection/strikes.yml` is private operational state and is included by normal plugin backups. Do not delete it during routine upgrades. A saved count of three also refuses login, including an interrupted native-ban write. To intentionally pardon an account, an operator must stop the server, back up the ledger, remove that account's ledger entry, apply native `/pardon`, and restart; merely removing a native ban does not erase confirmed history.

Operators can use `/westshieldprotection status` and `/westshieldprotection history <uuid>`. These require `westshieldprotection.admin` (operator by default) and player authentication. There is no player-accessible strike command or default bypass.

## Anti-xray and exploitation

Paper anti-xray **engine mode 3** is enabled per world in Survival and the Nether. The Nether list includes ancient debris; the Overworld list includes both ordinary and deepslate ores. The update radius is 2 and permission bypass is disabled. Air is deliberately not replaced with fake blocks, avoiding the documented client FPS cost. Creative and the closed End remain unobfuscated. Only outgoing block data changes; existing blocks, inventories and world seeds are retained.

Packet, reach, interaction and exploit checks are provided by the anti-cheats and the existing server. Anti-xray itself is obfuscation, not proof that a player installed an x-ray client; it does not generate automatic mining accusations or kick receipts. `/seed` remains available as previously requested. **A known world seed can bypass ore obfuscation through seed-based ore prediction**, as [Paper documents](https://docs.papermc.io/paper/anti-xray/). This installation does not regenerate ores or pretend to revoke knowledge of the existing seed.

## Build and acceptance

```sh
python3 westshield/english/build.py --server-root /path/to/server --dependency-directory /path/to/pinned-anticheats --only WestShieldProtection
```

The pinned dependency directory must contain GrimAC 2.3.74 and LightAntiCheat+ 2.0.2. The exact installed AuthMe/Floodgate/SimpleClans/Paper libraries come from the supplied server root. The build embeds `resources/words.txt` and its notices. Source is in `english/adapters/protection` and inherits the repository's GPL-3.0 license; upstream providers retain their own notices/licenses.

Run the 55 dictionary and persistence checks with Java/Javac 25 or newer:

```sh
python3 westshield/protection/tests/run.py --server-root /path/to/server
```

The [configuration examples](config-example/) contain the installed provider settings and only the anti-xray sections of world settings. Merge each world section into that world's `paper-world.yml`; do not replace unrelated world settings.

[verification.json](verification.json) records the exact installed artifacts and actual scope. Unit checks cover vocabulary/false-positive examples, Unicode handling, counter replay/reload/threshold and failed/malformed persistence. Isolated full-stack Java protocol tests cover visible chat/private messages, ordinary/manual/cancelled kick exclusions, real provider thresholds, three confirmed disconnects, native permanent bans and restart persistence. A separate network test sends actual impossible rotation packets through Grim and verifies a single confirmed kick; the banned account is also refused after a further restart. Test observers and accounts are never deployed on production. Synthetic provider flag injection checks the punishment pipeline, not independent detector accuracy; physical Bedrock play and 200-player load capacity are not established.
