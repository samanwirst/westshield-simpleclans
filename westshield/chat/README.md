# Clan names in chat

The prepared SimpleClans 2.19.2-westshield.2 increment adds
`settings.chat-use-clan-name`. With this setting enabled, the native label uses
the current full clan name and preserves the stored legacy/RGB clan color.
The tag used for commands, membership, creative trust and territories is unchanged.
Renames and administrative color changes are reflected by the next label read.
The setting defaults to false for configurations that do not opt in.

Use `tag.separator.char: ' | '` with both separator colors set to `8` and
`settings.use-colorcode-from-prefix-for-name: false` for a gray divider and white
player names. Local chat range, global `!` messages, censorship and recipients
remain controlled by the existing protection adapter.

On 2026-10-29 only those existing configuration options were applied live using
`clan admin reload`, which reloads SimpleClans settings and data without restarting
Minecraft. The full-name setting is saved but is ignored by the currently running
westshield.1 code. The new JAR is prepared locally for a later explicitly permitted
server restart; it was not uploaded over the active JAR or dynamically loaded.

The added label checks cover RGB preservation, legacy styles, literal names and
formatting isolation. They do not establish production activation of the new code.
Recompiling the upstream Clan class also requires replacing the removed
HIDE_POTION_EFFECTS enum constant with its current HIDE_ADDITIONAL_TOOLTIP name.
