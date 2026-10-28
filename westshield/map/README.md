# WestShield map presentation

Original web-only extensions for BlueMap 5.29, deployed 2026-10-28.
The existing portal serves the map entry point; script/style URLs are configured
in BlueMap webapp.conf and its generated settings.json. No server restart or
terrain rerender is required.

- Fixed 45-degree oblique orthographic view (default), plus top-down view.
- Normal panning and zoom; orientation and target height stay fixed.
- Daylight, no coordinate input/compass or click-coordinate popup.
- Existing player-visibility policy and clan-territory overlay remain unchanged.
- URL guard remains in the existing portal: only the map ID stays in the hash.
- The per-browser view preference is stored locally.

The native MapControls height calculation is unsuitable for a tilted orthographic
view: the wrapper restores a fixed target height before updating the camera.
No free-flight or perspective view is enabled.

Verified in Chromium against the public map: both modes, pan/zoom, click-popup
suppression, URL privacy, fixed daylight and mobile control placement.
