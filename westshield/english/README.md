# English server presentation

The WestShield server uses English for its own plugin messages, command help, errors, travel prompts, clan territory actionbars/wand, new PvP trophy names, TAB text, authentication welcome, locked-End message and public map/voice/Plan interfaces. Gameplay commands, persisted clan/world identities, inventories and gameplay policy are retained. Administrative adapter commands and permission nodes use the WestShield prefix. Player-written names/messages and historical player content are not translated. Minecraft's local menus and client mods still follow the player's client language.

## Server message renderer

`WestShieldEnglish` renders translatable server system messages, actionbars, disconnect reasons and inventory window titles using English, including vanilla command errors sent to a Russian-language client. It uses the installed PacketEvents 2.14.0 API and Adventure 5.2 `TranslationStore` on Purpur 26.2 / Java 25. It does not rewrite signed player chat, client settings, item profiles or authentication.

The English dictionary is read from the installed Geyser cache, `plugins/Geyser-Spigot/locales/en_us.json`, and verified against its build-time SHA-256. No Minecraft language asset is redistributed in this repository or JAR. Rebuild the adapter against the installed dictionary when changing Geyser/Minecraft versions. `/westshieldenglish` reports active state, dictionary size, translation count and failures to operators; it exposes no message text.

`tests/EnglishCheck.java` checks semantic output and preservation of indexed/repeated arguments, apostrophes, literal braces/percent signs, colors, click actions, player-written literal text and unknown keys. The real loopback scenario also verifies an English vanilla command error and a zero-failure renderer status.

## Configuration policy

Use the existing installed English message packs and these explicit language settings:

| Plugin | Setting |
| --- | --- |
| AuthMe | `settings.messagesLanguage: en`, `settings.perPlayerLocale: false`; English `welcome.txt` |
| GSit | `Lang.lang: en_us`, `Lang.client-lang: false`; use literal `Sneak` in its server prompt |
| SimpleClans | `settings.language: en`, `settings.user-language-selector: false` |
| Multiverse Core/Inventories | `messaging.default-locale: en`, `messaging.per-player-locale: false` |
| LuckPerms | `auto-install-translations: false`; retain the downloaded translation repository outside the active plugin folder so bundled English is used |
| OpenInv | External English copies for every bundled `locale/*.yml`, retaining its installed English pack |
| CoreProtect / Chunky | `language: en` |
| Geyser / Floodgate | Existing `default-locale: en_US`; complete English overrides are needed because that default alone does not disable client locale selection |
| Plan | `Plugin.Logging.Locale: EN`; public React resource overrides force English and expose only English in its language choice |
| TAB / Simple Voice Chat | Existing English message packs; English header/footer, scoreboard and voice reminder text |

Geyser supports external overrides in `plugins/Geyser-Spigot/languages/<locale>.properties`. Copy its installed `languages/texts/en_US.properties` into each supported locale filename. Do not modify downloaded Java item-language assets. Floodgate's pinned build loads bundled messages, so `floodgate_text_overlay.py` replaces only those resources with its English bundle and proves every other original file is unchanged. Preserve the upstream JAR for rollback. Upstream classes and license notices remain intact.

## Web presentation

BlueMap `lang/settings.conf` selects only English, disables browser detection, and retains the independent English **Clan territories** filter. Its existing compiled bundle changes only the URLs used to fetch language/webapp settings, with matching cache revisions on the entry script and clan-filter script. Flat navigation, fixed daylight, hidden coordinates/compass/players and the disabled coordinate-click card remain active.

The voice page resets an old saved language to English, hides the language picker, and overrides `web/js/utils/lang.js` to expose/accept only English. Audio/PTT/authentication logic is retained. Plan's supported `public_html` overrides retain its installed React chunks and API/authentication behavior while fixing its locale to `EN`. Its entry clears an old saved locale before module admission. English behavior is tested using a Russian browser and old Russian/Polish preferences, not just a fresh English browser.

See [Geyser's override documentation](https://geysermc.org/wiki/geyser/translations/), [Plan localization](https://github.com/plan-player-analytics/Plan/wiki/Localization) and [Plan public resource overrides](https://github.com/plan-player-analytics/Plan/wiki/Html-Customization).

## Build

Install Java/Javac supporting release 25 and Python with PyYAML, then use a prepared server's exact libraries/plugins:

```sh
python3 westshield/english/build.py --server-root /path/to/purpur-server
python3 westshield/english/floodgate_text_overlay.py --upstream-jar /path/to/floodgate-spigot.jar --output /path/to/floodgate-spigot-westshield-en.jar
```

The adapter build includes WestShieldCreative 1.0.3, WestShieldWorldRules 1.1.2, WestShieldHeads 1.0.2 and WestShieldEnglish 1.0.1. Build SimpleClans 2.19.2-westshield.1 and WestShieldClanTerritories 1.0.2 separately using its existing build script. Do not install old/new copies of the same plugin together or upload test probes. Replace only the corresponding current JARs after a consistent backup and clean stop.

## Acceptance

Current WestShield acceptance is recorded in [rebranding/verification.json](../rebranding/verification.json), with exact artifact hashes and separate isolated/production check lists. The isolated Java protocol and browser run covers 26 checks; geometry/storage covers 22 cases.

The preceding English-localization increment passed 37 native checks for its original seven artifacts, including an English-named PvP head with a static skin, and independent browser/message-style checks. Those historical artifact names, versions and hashes remain unchanged in [the original receipt](../../docs/history/pre-westshield/english/verification.json). They are not recast as new WestShield measurements. Physical Bedrock GUI/controller or microphone operation is not established by Java protocol and browser checks.

## Rebranding and previous verification

The WestShield build also includes WestShieldCombat 1.0.1 and WestShieldPortal 1.0.2. Previous English-localization receipts are preserved, unchanged, in `docs/history/pre-westshield/english/verification.json`; their old artifact names and hashes are historical. New acceptance is recorded separately for the exact WestShield artifacts. See [rebranding](../REBRANDING.md) for stable private storage identities.

WestShieldWelcome 1.0.0 is a separate optional presentation adapter; see [the authenticated welcome guide](../welcome/README.md).
