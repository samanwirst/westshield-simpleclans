"""Exercise the isolated BlueMap in real desktop/mobile Chromium."""
from pathlib import Path
import json
import time
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent / "browser-results"
ROOT.mkdir(exist_ok=True)
report = {"status": "running", "environment": "loopback isolated fixture, Chromium", "checks": []}

def check(name, value):
    report["checks"].append({"check": name, "passed": bool(value)})
    print(("PASS " if value else "FAIL ") + name, flush=True)
    assert value, name

def wait_layer(page):
    page.wait_for_function("window.bluemap?.mapViewer?.markers?.markerSets?.has('westshield-clans')", timeout=90000)
    page.wait_for_timeout(800)

def focus_polygon(page):
    page.evaluate("""() => {
        const a=window.bluemap,m=a.mapViewer.controlsManager;
        a.setFlatView(0);m.position.set(320,64,320);m.distance=95;
        a.mapControls.reset();a.mapViewer.redraw();
    }""")
    page.wait_for_timeout(1200)

try:
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,
            args=['--use-angle=swiftshader','--enable-unsafe-swiftshader','--disable-dev-shm-usage'])
        context=browser.new_context(viewport={"width":1365,"height":900},locale='ru-RU')
        page=context.new_page()
        errors=[]
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.goto('http://127.0.0.1:25603/',wait_until='domcontentloaded')
        wait_layer(page)
        focus_polygon(page)
        box=page.locator('#westshield-clan-filter input')
        check('desktop dedicated layer checkbox visible and checked',box.is_visible() and box.is_checked())
        state=page.evaluate("""() => {
            const a=window.bluemap,l=a.mapViewer.markers.markerSets.get('westshield-clans');
            const area=l.markers.get('polyland-area'),name=l.markers.get('polyland-name');
            return {markerCount:l.markers.size,visible:l.visible,areaVisible:area.visible,nameVisible:name.visible,
              name:name.element.textContent, labelRect:name.element.getBoundingClientRect().toJSON(),
              data:l.data.markers,managerKeys:Object.keys(a.mapViewer.controlsManager),
              state:a.appState.controls.state,views:a.mapViewer.map.data.views};
        }""")
        (ROOT/'browser-layer-state.json').write_text(json.dumps(state,indent=2,ensure_ascii=False)+'\n')
        check('only mapped-world territory area and permanent name published',state['markerCount']==2 and state['name']=='Reborn Crew')
        check('permanent clan name visible on the screen without a click',page.locator('.westshield-clan-label').is_visible())
        check('clan area and name are in a single visible layer',state['visible'] and state['areaVisible'] and state['nameVisible'])
        check('flat orthographic mode retained',state['state']=='flat' and page.evaluate('window.bluemap.mapViewer.controlsManager.ortho')==1)
        page.screenshot(path=str(ROOT/'map-clans-desktop.png'))
        box.uncheck()
        page.wait_for_timeout(650)
        check('checkbox hides both territory fill and permanent name',page.evaluate("""() => {
            const l=window.bluemap.mapViewer.markers.markerSets.get('westshield-clans');
            const shown=m=>{for(let o=m;o;o=o.parent)if(!o.visible)return false;return true;};
            return !l.visible && !shown(l.markers.get('polyland-area')) && !shown(l.markers.get('polyland-name'));
        }"""))
        check('hidden name removed from visible DOM',not page.locator('.westshield-clan-label').is_visible())
        page.screenshot(path=str(ROOT/'map-clans-hidden.png'))
        page.reload(wait_until='domcontentloaded');wait_layer(page)
        check('hidden preference survives a browser reload',not box.is_checked() and page.evaluate("!window.bluemap.mapViewer.markers.markerSets.get('westshield-clans').visible"))
        page.evaluate("window.bluemap.switchMap('westshield_nether')")
        wait_layer(page)
        check('filter follows dimension changes and respects saved preference',not box.is_checked() and page.evaluate("window.bluemap.mapViewer.map.data.id==='westshield_nether' && !window.bluemap.mapViewer.markers.markerSets.get('westshield-clans').visible"))
        page.evaluate("window.bluemap.switchMap('westshield')")
        wait_layer(page);focus_polygon(page)
        check('returning to overworld preserves hidden clan layer',not box.is_checked() and not page.locator('.westshield-clan-label').is_visible())
        box.check();page.wait_for_timeout(700)
        check('checking restores fill and permanent names',page.locator('.westshield-clan-label').is_visible() and page.evaluate("window.bluemap.mapViewer.markers.markerSets.get('westshield-clans').visible"))
        page.evaluate("""() => {const l=window.bluemap.mapViewer.markers.markerSets.get('westshield-clans');l.visible=false;l.data.saveState();window.bluemap.mapViewer.redraw();}""")
        page.wait_for_timeout(800)
        check('dedicated checkbox synchronizes with native BlueMap layer state',not box.is_checked())
        box.check();page.wait_for_timeout(600)
        before=page.evaluate('window.bluemap.mapViewer.controlsManager.position.toArray()')
        page.mouse.move(660,440);page.mouse.down();page.mouse.move(740,500,steps=12);page.mouse.up();page.wait_for_timeout(900)
        after=page.evaluate('window.bluemap.mapViewer.controlsManager.position.toArray()')
        check('ordinary mouse navigation still pans the map',before!=after)
        distance=page.evaluate('window.bluemap.mapViewer.controlsManager.distance')
        page.mouse.wheel(0,-240);page.wait_for_timeout(900)
        check('ordinary wheel still zooms in flat view',page.evaluate('window.bluemap.mapViewer.controlsManager.distance')<distance)
        page.mouse.click(670,450);page.wait_for_timeout(700)
        privacy=page.evaluate("""() => {
            const a=window.bluemap;
            const hidden=s=>[...document.querySelectorAll(s)].every(e=>getComputedStyle(e).display==='none'||e.getBoundingClientRect().width===0);
            a.mapViewer.data.uniforms.sunlightStrength.value=0;
            return {sun:a.mapViewer.data.uniforms.sunlightStrength.value,popupDisabled:a.westshieldPopupCoordinatesDisabled,
              popupVisible:a.popupMarker.visible,popupText:a.popupMarker.element.textContent,
              positionHidden:hidden('.position-input'),compassHidden:hidden('.compass'),daySwitchHidden:hidden('.day-night-switch'),
              hash:location.hash,playerMarkers:[...a.mapViewer.markers.markerSets.keys()].filter(x=>/player/i.test(x))};
        }""")
        report['privacy']=privacy
        check('daylight remains fixed even after a lighting write',privacy['sun']==1)
        check('map clicks reveal no coordinate popup',privacy['popupDisabled'] and not privacy['popupVisible'] and not privacy['popupText'])
        check('coordinate direction and lighting controls stay hidden',privacy['positionHidden'] and privacy['compassHidden'] and privacy['daySwitchHidden'])
        check('URL retains dummy coordinates rather than actual navigation position',privacy['hash']=='#westshield:0:0:0:1500:0:0:0:1:flat')
        check('no public player marker layer introduced',not privacy['playerMarkers'])
        check('desktop has no JavaScript errors',not errors)
        mobile=browser.new_context(viewport={"width":390,"height":844},device_scale_factor=2,is_mobile=True,has_touch=True,locale='ru-RU')
        mp=mobile.new_page();mobile_errors=[]
        mp.on('pageerror',lambda e:mobile_errors.append(str(e)))
        mp.goto('http://127.0.0.1:25603/',wait_until='domcontentloaded');wait_layer(mp);focus_polygon(mp)
        mb=mp.locator('#westshield-clan-filter input')
        check('mobile dedicated checkbox visible and touch accessible',mb.is_visible() and mp.locator('#westshield-clan-filter').bounding_box()['width']<390)
        mp.screenshot(path=str(ROOT/'map-clans-mobile.png'))
        mb.tap();mp.wait_for_timeout(650)
        check('mobile tap hides fill and clan name',not mb.is_checked() and not mp.locator('.westshield-clan-label').is_visible() and mp.evaluate("!window.bluemap.mapViewer.markers.markerSets.get('westshield-clans').visible"))
        mb.tap();mp.wait_for_timeout(650)
        check('mobile tap restores permanent clan name',mb.is_checked() and mp.locator('.westshield-clan-label').is_visible())
        mp.touchscreen.tap(195,400);mp.wait_for_timeout(500)
        check('mobile map tap reveals no coordinate card',mp.evaluate("window.bluemap.westshieldPopupCoordinatesDisabled && !window.bluemap.popupMarker.visible && !window.bluemap.popupMarker.element.textContent"))
        check('mobile has no JavaScript errors',not mobile_errors)
        browser.close()
    report['status']='passed'
finally:
    report['completedAt']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
    (ROOT/'browser-verification.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
