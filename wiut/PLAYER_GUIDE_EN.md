# Clans and creative claims

- Administrators create clans. Ask a clan leader for an invitation.
- `/clan invite PlayerName` invites a player; `/accept` accepts the invitation.
- `/clan roster` shows members; `/clan list` shows clans.
- Leaders can use `/clan kick PlayerName` to remove a member.
- `/clan resign` leaves after confirmation. The final leader leaving disbands the clan.
- `/clan disband` disbands your clan after confirmation.

Use the full server name for a Bedrock player, including the dot prefix, for example `.Nickname`.

Stand inside your creative claim:

- `/trust [group.wiut]` grants building and container access to clan tag `wiut`.
- `/containertrust [group.wiut]` grants container access without building.
- `/accesstrust [group.wiut]` grants access to doors, buttons and levers.
- `/untrust [group.wiut]` removes that clan's access.
- `/trustlist` lists grants.

You can grant access to your own or another clan. New members receive clan access immediately; members who leave or are removed lose it. Individual `/trust PlayerName` grants remain independent. After disbanding a clan permanently, remove an unused group trust entry: a future clan reusing the same tag would match it.

Clans work across worlds. Protected claims exist only in creative. AuthMe registration/login is required.

Administrators can mark informational clan territories with arbitrary polygon boundaries. Crossing a boundary displays the clan name above the hotbar. These labels do not restrict building, containers or interaction and do not grant access to creative claims.

BlueMap shows a translucent clan-colored fill and permanent clan name. The **Clan territories** checkbox shows/hides that layer and remembers your preference. Administrators choose one RGB color for the chat tag and map. Bedrock text uses its supported palette; the browser map preserves the exact RGB.
