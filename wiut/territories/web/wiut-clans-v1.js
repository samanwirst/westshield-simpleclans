/* A dedicated clan-layer filter; existing WIUT camera/privacy presentation stays active. */
(() => {
    const app = window.bluemap;
    if (!app?.mapViewer?.markers?.markerSets) {
        console.error("WIUT clan filter: BlueMap marker interface is unavailable.");
        return;
    }
    if (document.getElementById("wiut-clan-filter")) return;
    const storageKey = "wiut.clan-territories.visible";
    const readPreference = () => {
        try {
            const value = localStorage.getItem(storageKey);
            return value === null ? null : value === "true";
        } catch { return null; }
    };
    const persist = (value) => {
        try { localStorage.setItem(storageKey, String(value)); } catch { /* Private browsing still permits the live filter. */ }
    };
    let preference = readPreference();
    let observedLayer = null;
    let observedVisible = null;
    const label = document.createElement("label");
    label.id = "wiut-clan-filter";
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = preference ?? true;
    checkbox.setAttribute("aria-label", "Clan territories");
    const title = document.createElement("span");
    title.textContent = "Clan territories";
    label.append(checkbox, title);
    document.body.append(label);

    const currentLayer = () => app.mapViewer.markers.markerSets.get("wiut-clans");
    const apply = (layer, visible) => {
        layer.visible = visible;
        layer.data.saveState();
        observedVisible = visible;
        app.mapViewer.redraw();
    };
    checkbox.addEventListener("change", () => {
        preference = checkbox.checked;
        persist(preference);
        const layer = currentLayer();
        if (layer) apply(layer, preference);
    });
    const sync = () => {
        const layer = currentLayer();
        if (!layer) { observedLayer = null; return; }
        if (layer !== observedLayer) {
            observedLayer = layer;
            if (preference === null) preference = layer.visible;
            checkbox.checked = preference;
            apply(layer, preference);
        } else if (layer.visible !== observedVisible) {
            // Keep the dedicated switch in sync with BlueMap's built-in layer switch.
            observedVisible = layer.visible;
            preference = layer.visible;
            checkbox.checked = preference;
            persist(preference);
        }
    };
    sync();
    window.setInterval(sync, 500);
})();
