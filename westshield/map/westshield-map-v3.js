/* WestShield presentation for BlueMap 5.29. Navigation remains available in flat view. */
(() => {
    const app = window.bluemap;
    if (!app?.mapViewer?.data?.uniforms?.sunlightStrength) {
        console.error("WestShield map: BlueMap's lighting interface is unavailable.");
        return;
    }
    // BlueMap invokes this instance handler for both mouse clicks and touch taps.
    // Suppress the coordinate card and its clipboard handlers without blocking navigation.
    const popup = app.popupMarker;
    if (popup) {
        popup.onMapInteraction = () => {};
        popup.animation?.cancel();
        popup.visible = false;
        popup.cube.visible = false;
        popup.element.replaceChildren();
    }
    if (!app.westshieldPopupCoordinatesDisabled) {
        Object.defineProperty(app, "westshieldPopupCoordinatesDisabled", {value: true});
    }
    if (app.westshieldMapPresentationV3) return;
    Object.defineProperty(app.mapViewer.data.uniforms.sunlightStrength, "value", {
        configurable: true,
        enumerable: true,
        get: () => 1,
        set: () => {},
    });
    const syncMenu = () => {
        const menu = app.appState.menu;
        const page = menu.isOpen ? menu.currentPage().id : "closed";
        if (document.documentElement.dataset.westshieldMapMenu !== page) {
            document.documentElement.dataset.westshieldMapMenu = page;
        }
    };
    const observer = new MutationObserver(syncMenu);
    observer.observe(document.getElementById("app"), {childList: true, subtree: true});
    syncMenu();
    Object.defineProperty(app, "westshieldMapPresentationV3", {value: "1.1.1"});
    app.mapViewer.redraw();
})();

/* WestShield fixed orthographic views for BlueMap 5.29. */
(() => {
    const app = window.bluemap;
    if (!app?.mapControls || !app?.mapViewer?.controlsManager || app.westshieldOrthographicV1) return;
    const manager = app.mapViewer.controlsManager;
    const lockViews = () => {
        for (const map of app.maps) {
            map.data.perspectiveView = false;
            map.data.freeFlightView = false;
            map.data.views = ["flat"];
        }
    };
    lockViews();
    const switchMap = app.switchMap.bind(app);
    app.switchMap = async (...args) => { const result = await switchMap(...args);lockViews();return result; };
    app.setPerspectiveView = () => app.setFlatView(0);
    app.setFreeFlight = () => app.setFlatView(0);
    let lastQualityUpdate = 0;
    const improveDetail = () => {
        const now = performance.now();
        if (now - lastQualityUpdate < 500 || !app.mapViewer.map) return;
        lastQualityUpdate = now;
        const height = Math.tan(manager.camera.fov * Math.PI / 360) * manager.distance;
        const width = height * manager.camera.aspect;
        const footprint = (width + height / Math.cos(manager.angle)) / Math.SQRT2;
        const budget = innerWidth < 600 ? 320 : 576;
        const radius = Math.min(budget, Math.max(160, Math.ceil((footprint + 64) / 32) * 32));
        const viewer = app.mapViewer;
        const center = viewer.data.loadedCenter;
        if (viewer.data.loadedHiresViewDistance !== radius ||
            Math.abs(center.x - manager.position.x) >= 32 ||
            Math.abs(center.y - manager.position.z) >= 32) {
            viewer.loadMapArea(manager.position.x, manager.position.z, radius);
        }
    };
    let mode = "oblique";
    try { if (localStorage.getItem("westshield.map.view") === "top") mode = "top"; } catch (_) {}
    const apply = () => {
        manager.ortho = 1;
        manager.angle = mode === "oblique" ? Math.PI / 4 : 0;
        manager.rotation = mode === "oblique" ? Math.PI / 4 : 0;
        manager.tilt = 0;
        manager.position.y = mode === "oblique" ? 64 : 0;
    };
    // Retain normal pan/zoom, then pin orientation before the camera is updated.
    // A tilted orthographic camera needs a fixed target height: stock MapControls
    // otherwise leaves it at its temporary -10000 value when mapHeight is skipped.
    const update = app.mapControls.update.bind(app.mapControls);
    app.mapControls.update = (delta, map) => { update(delta, map);apply();improveDetail(); };
    for (const name of ["mouseRotate", "mouseAngle", "keyRotate", "keyAngle", "touchRotate", "touchAngle"]) {
        const control = app.mapControls[name];
        if (control) control.update = () => {};
    }
    const group = document.createElement("div");
    group.className = "westshield-map-views";
    group.setAttribute("role", "group");
    group.setAttribute("aria-label", "Map view");
    const buttons = new Map();
    const select = selected => {
        mode = selected;
        app.viewAnimation?.cancel();
        app.mapControls.reset();
        if (app.mapViewer.map) manager.controls = app.mapControls;
        apply();
        manager.updateCamera();
        app.mapViewer.redraw();
        for (const [key, button] of buttons) button.setAttribute("aria-pressed", String(key === mode));
        try { localStorage.setItem("westshield.map.view", mode); } catch (_) {}
    };
    for (const [key, label] of [["oblique", "Orthographic"], ["top", "Top down"]]) {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = label;
        button.addEventListener("click", () => select(key));
        buttons.set(key, button);
        group.append(button);
    }
    document.body.append(group);
    // New map selections still use BlueMap's ordinary flat-view setup. Our update
    // wrapper applies the selected fixed orientation to the next rendered frame.
    const flat = app.setFlatView.bind(app);
    app.setFlatView = (_transition = 0, minimumDistance = 5) => {
        flat(0, minimumDistance);
        apply();
    };
    Object.defineProperty(app, "westshieldOrthographicV1", {value: {
        version: "1.1.1", get mode() { return mode; }, select
    }});
    const resetCamera = app.resetCamera.bind(app);
    app.resetCamera = () => {
        resetCamera();
        manager.distance = 200;
        select(mode);
    };
    // Open at a readable scale instead of BlueMap's distant low-resolution overview.
    if (manager.distance >= 1000) manager.distance = 200;
    select(mode);
})();
