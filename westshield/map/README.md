# WestShield map presentation

Original web extensions for BlueMap 5.29, deployed 2026-10-28.
The existing portal serves the map entry point; script/style URLs are configured
in BlueMap webapp.conf and its generated settings.json. Minecraft does not need
to restart. Upgrading a map previously rendered with only flat view enabled
requires rerendering its geometry as described below.

- Fixed 45-degree oblique orthographic view (default), plus top-down view.
- Normal panning and zoom; orientation and target height stay fixed.
- Daylight, no coordinate input/compass or click-coordinate popup.
- Existing player-visibility policy and clan-territory overlay remain unchanged.
- URL guard remains in the existing portal: only the map ID stays in the hash.
- The per-browser view preference is stored locally.
- Adaptive detailed-tile loading follows the viewport and panning, with bounded
  desktop/mobile radii. New sessions and map resets open at a readable scale.

The native MapControls height calculation is unsuitable for a tilted orthographic
view: the wrapper restores a fixed target height before updating the camera.
The browser exposes only the two fixed orthographic orientations.

## Exterior geometry

BlueMap 5.29 omits side faces when both perspective and free-flight are disabled
in the renderer configuration. Tilting that top-only model exposes black gaps.
Use `rendering.conf` as an overlay for the overworld map configuration, then
reload BlueMap alone with `bluemap reload light`. Enable perspective in the
renderer to generate the exterior geometry; the browser extension removes that
navigation mode, disables free flight and pins the camera orientation.

Run `bluemap force-update <map-id>` once to replace existing top-only tiles.
Keep the existing render mask, world settings, player visibility and clan layers.
One render thread limits contention with active players. The full rerender runs
in the background; updated and old tiles can coexist until it completes.

The ambient light setting slightly lifts shaded surfaces while sunlight stays
fixed at daytime. It does not expose a lighting control to players.

BlueMap 5.29's removed-region scan adds historical regions after its radius
filter. Temporarily disabling that scan can prioritize one existing region;
restore the original setting before leaving a full update running.

Verified in Chromium against the public map: both modes, pan/zoom, click-popup
suppression, URL privacy, fixed daylight and mobile control placement.
The older `orthographic-verification.json` is the historical browser-only check;
it did not verify side-face geometry. See the subsequent quality verification
for the corrected rendering and its scope.
