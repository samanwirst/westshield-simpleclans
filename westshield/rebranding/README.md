# WestShield server identities

This increment rebrands the server presentation, the six server-owned adapters, the clan overlay, territory layer and administrative commands. Stable world and player storage identities stay unchanged. Existing territory selection tools keep a read-only compatibility key; new tools use the WestShield key.

The exact artifacts are listed in `verification.json`. The fixture validates existing AuthMe accounts, isolated creative inventories and return to survival, clan administration restrictions, existing polygon markers, map switching/filtering, orthographic daylight and coordinate privacy, voice and analytics HTTP pages. Geometry/storage checks cover 22 cases. These checks do not establish a physical Bedrock client or microphone session. Earlier harness failures and original pre-rebranding receipts remain private/historical; they are not rewritten as successful checks.

Build the clan overlay with `westshield/build.py`, territories with `westshield/territories/build.py`, and all six adapters with `westshield/english/build.py`. Rebuild Floodgate's English resources with `westshield/english/floodgate_text_overlay.py`; no Floodgate classes change.

Deployment moves existing custom plugin folders as units, retains all state files, renames map output directories in place and preserves the original map tiles. Never install old and new plugin copies together. Make an offline backup first and retain existing world/inventory/claim keys.
