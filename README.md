# Recom3 Maps

Current version: **0.2.2** (versionCode **4**). Phone tile downloads now wait for both the accessory connection and the firmware's web-ready state. A stalled SDK request is discarded with its private worker process, allowing later requests to recover without restarting Maps. The direct Wi-Fi route and tile cache remain available on firmware without the web SDK. The matching web catalog icon is [available here](artwork/recom3_maps_icon.png).

![Recom3 Maps icon](artwork/recom3_maps_icon.png)

Online OpenStreetMap raster maps for the Recon Jet and Recon Snow2 HUDs. Recom3 Maps is a native Java Android application with a 428 × 240 landscape interface, GPS follow, heading, pan, zoom and an on-device HTTP tile cache. Package name `com.recom3.maps`. Minimum and target API 16. The debug APK contains the ARMv7 TLS library for the Recon hardware.

The physical Snow2 loaded live tiles over the ReconOS SDK through a patched Snow3 on a Huawei P10. Jet testing covered direct Wi-Fi on an older unit and iPhone MFi tile downloads on a later unit. See [Jet firmware compatibility](#jet-firmware-compatibility), [the Recon SDK hardware test](evidence/SDK-RESULTS.md) and the [initial Wi-Fi test report](evidence/RESULTS.md).

## Jet firmware compatibility

**ReconOS 4.1 and 4.6 are Jet firmware versions. Android 4.1.2 is the underlying Android version, not the ReconOS version.** An Android version number or successful Bluetooth pairing does not establish that the firmware exposes the web SDK required by Maps.

The following results describe two inspected Jet units, using the owner's reported ReconOS version labels. They are not a guarantee for every firmware build or casing color. Maps checks the available firmware services at runtime.

| Jet firmware | Web SDK on the inspected unit | Phone tile downloads | Connection to use |
| --- | --- | --- | --- |
| ReconOS **4.1** | Required connectivity classes absent. | Unavailable through Maps' ReconOS SDK route, even when the iPhone pairs. | **Direct Wi-Fi**, including a phone hotspot. |
| ReconOS **4.6** | Required connectivity classes present; connectivity service registered. | **Verified with an iPhone over MFi in Maps 0.2.1**: live tile responses were received. | Phone bridge when the companion's HTTP session is ready; direct Wi-Fi is also supported by Maps. |

The inspected 4.1 library lacked `HUDOS`, `HUDConnectivityManager`, `HUDHttpRequest` and `HUDHttpResponse`; all four were present in the inspected 4.6 library. The older unit logged `NoClassDefFoundError`. Installing the development SDK on a computer does not add these runtime classes or their backing services to the goggles. Copying a newer framework JAR alone is not a supported firmware upgrade. For now, use Wi-Fi on the older unit.

The SDK owns different phone transports: **Android uses Bluetooth SPP; iPhone uses MFi accessory sessions**. Android SPP was verified separately on Snow2 with the patched Snow3 companion; that result does not prove the same reconnect behavior on Jet or iPhone. On the 4.6 Jet, an iPhone reconnect restored command/object/file sessions before the HTTP session, and tile requests timed out. Restarting the iPhone companion restored the HTTP route and tile responses. The Maps-side recovery fix is tracked in [PR #2](https://github.com/recom3/recom3-maps/pull/2); those observed results do not validate the corrected build on hardware.

| Maps status | Meaning and next step |
| --- | --- |
| **Recon SDK web unavailable** | The required firmware API cannot be used. On the inspected 4.1 Jet, use direct Wi-Fi. |
| **Waiting for phone web** | The phone's HTTP route is not ready. Check the companion connection; Bluetooth pairing alone is insufficient. |
| **Phone bridge** | Maps has selected the phone route. Confirm a new tile response to distinguish a live download from cached content. |
| **Phone tile request failed** | A request failed or timed out. This message alone does not prove that the SDK is missing. |

See [the dated firmware comparison](evidence/SDK-RESULTS.md#jet-firmware-follow-up-2026-11-25) for the observed scope, and [IP connection instructions](#share-an-android-ip-connection) for Wi-Fi setup.

## Build and install

Install **JDK 8**, Android SDK **Platform 27**, **Build Tools 27.0.3**, and **Platform Tools**. The included wrapper uses Gradle 4.1 with Android Gradle Plugin 3.0.1. The first build needs access to the Gradle distribution, Google Maven and Maven Central. Install the **Recon Instruments SDK Add-On for API 16** under the Android SDK, or set `RECON_SDK_JAR` to its `libs/com.reconinstruments.os.jar`. The APK uses the optional framework libraries provided by compatible goggles.

```powershell
$env:JAVA_HOME = 'C:\Java\jdk8'
$env:ANDROID_HOME = 'C:\Android\Sdk'
.\gradlew.bat :app:assembleDebug
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb devices
& $adb -s JET_SERIAL install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s JET_SERIAL shell am start -n com.recom3.maps/.MapsActivity
```

Use the serial shown by `adb devices`. If installing the supplied APK, substitute `dist\Recom3-Maps-debug.apk` for the build output path. The debug APK is signed for local testing with a standard Android debug key.

## Connect an iPhone or Snow3 via the ReconOS SDK

1. Connect the goggles from the companion app and enable the phone's internet access.
2. Turn off Wi-Fi on the goggles while testing serial and verify that there is no other active Android IP route.
3. Open Recom3 Maps and zoom to an uncached viewport. **Phone bridge** in the HUD means that the firmware reports both a companion connection and a web route. **Waiting for phone web** means the HTTP route is not ready, even if Bluetooth is connected. A `Tile Recon SDK … HTTP 200` line from `adb logcat -s Recom3Maps` confirms that the route returned an actual tile.

Recom3 Maps uses the same `HUDOS.getHUDService(HUDOS.HUD_CONNECTIVITY_SERVICE)` and `HUDConnectivityManager.sendWebRequest()` calls as the SDK build of Snow2Liftie. ReconOS owns accessory pairing, Android SPP and iPhone MFi sessions. The companion executes HTTPS requests on the phone. Jet firmware varies: an older tested unit lacks the SDK connectivity classes, while a different Jet successfully returned tiles over an iPhone MFi web session. **Recon SDK web unavailable** is the status for unsupported firmware; use direct Wi-Fi on those units.

After a connection loss, Maps checks the web route again in the background. It does not issue new phone tile requests on accessory connectivity alone. SDK calls run in a private, non-exported `:phoneweb` process, with a separate event Looper so connection changes remain responsive. A probe has a five-second limit and a tile request has a fifteen-second limit. If the helper dies or a call exceeds its limit, Maps retires that helper before creating another. This also discards app-side callers stuck in firmware Binder requests that ignore thread interruption. Cached tiles remain visible while the phone's web route reconnects. Maps cannot reopen an iPhone app's accessory sessions itself; the companion must restore its HTTP session.

## Recovery regression checks

```powershell
.\gradlew.bat phoneWebRegression :app:assembleDebug :app:lintDebug
```

The host regression task exercises accessory-only readiness, stale web state, request timeout and replacement, helper death, interruption, shutdown during a pending call, late replies and binary HTTP responses. It uses fake transport channels and does not access a phone or the network. Physical iPhone/Android reconnect testing is still required for this release; see [the recovery change report](evidence/PHONE-RECOVERY.md).

For the tested Android setup, build Snow3 with the [companion patch](evidence/snow3-serial-bridge.patch). It applies to Snow3 commit `5a0a7a2`. From that checkout, run `git apply --check PATH_TO_PATCH`, apply it locally, then build and install via Android Studio. When Snow3 shows duplicate device names, pick the entry whose Bluetooth address belongs to the connected goggles.

## Share an Android IP connection

Recom3 Maps uses the active Android IP route when available.

- **Wi-Fi hotspot.** Enable mobile data and the phone's hotspot. Choose 2.4 GHz and WPA2 if offered. On iPhone, use **Maximize Compatibility** if available. In the Jet's Wi-Fi settings, select the hotspot and enter its password. If needed, open the Jet settings over USB with `adb -s JET_SERIAL shell am start -a android.settings.WIFI_SETTINGS`.
- **Bluetooth PAN.** Enable Bluetooth Internet tethering on the phone. On the glasses, select the paired phone's Internet access profile if ReconOS offers one. Disconnect other Wi-Fi while testing so the active route can switch to PAN. Availability depends on the phone and ReconOS build.
- Open Recom3 Maps and zoom once to a fresh viewport. A map loaded from cache can appear without internet. A newly loaded tile and a `Tile HTTPS … HTTP 200` entry from the glasses confirm end-to-end access. Use `adb -s JET_SERIAL shell dumpsys connectivity` to check the active Android route, and verify the hotspot's client list on the phone. Bluetooth pairing by itself does not establish a PAN route.

## HUD controls

| Control | Action |
| --- | --- |
| Optical swipe or D-pad | Pan north, south, west or east in **PAN** mode. Panning pauses GPS follow. |
| OK, Enter or controller A | Switch **PAN** and **ZOOM** modes. |
| Up or right in **ZOOM** | Zoom in one level. |
| Down or left in **ZOOM** | Zoom out one level. |
| Jet action key, mapped to Android `BUTTON_1` | Recenter on GPS and resume follow. |
| Back | Leave zoom mode, recenter when panned, or exit when already following in pan mode. |
| Keyboard + / − or volume keys | Zoom directly. |
| Touch input, where available | Drag to pan. Tap **−**, **+** or **GPS**. Tap the attribution to open the copyright page. |

The first fresh GPS fix centers the map near street level if no pan or zoom has been performed. **GPS** waits for a fix if necessary. The blue dot shows the current fix and its arrow shows heading when available. **M** is magnetic heading. **T** is true heading from magnetic declination or GPS course while moving. Use the reported accuracy and allow time for a cold GPS fix under open sky.

The top bar distinguishes a network link from tile loading or request failure. An offline viewport can use its fresh cached tiles. An uncached viewport may have blank areas until connectivity returns. If TLS fails, check the HUD clock and the provider's certificate chain.

## Test without a fresh GPS fix

Debug builds accept a temporary fallback position supplied by ADB. Use actual decimal latitude and longitude in the command below.

```powershell
& $adb -s JET_SERIAL shell am start -S -n com.recom3.maps/.MapsActivity --es test_lat LATITUDE --es test_lon LONGITUDE
```

The HUD shows **Test position** while using the supplied position. A fresh GPS fix takes priority. The test position and viewport changes from that launch are session-only.

## Tile service and production builds

The default debug build uses OSM Foundation's [Standard raster tiles](https://tile.openstreetmap.org/) for low-volume interactive development and personal testing. Read the [raster tile usage policy](https://operations.osmfoundation.org/policies/tiles/) and the [OSM attribution and license information](https://www.openstreetmap.org/copyright).

- Persistent on-map attribution credits OpenStreetMap contributors. Requests use `Recom3Maps/0.2.1 (hud@recom3.com)`.
- A single worker requests current-viewport tiles with at least 350 ms between request starts and waits for navigation to settle. It backs off after server errors, including `Retry-After`, and has no prefetch or bulk download mode.
- Android's [HttpResponseCache](https://developer.android.com/reference/android/net/http/HttpResponseCache) handles freshness and revalidation for the direct route. The SDK route keeps its own cache of binary tiles and HTTP validators. Each cache is limited to 12 MiB, and bitmap memory is limited to the viewport. The offline route checks both caches for fresh tiles.
- Tile requests disclose the viewed map tiles to the provider by design. A GPS-following viewport may reveal the wearer's approximate location. The debug logs omit coordinates. The viewport and the cache are stored locally in app-private storage.

For production distribution, configure an authorized 256 × 256 HTTPS raster XYZ endpoint and the provider's required attribution. Keep the credit short enough for the 428-pixel HUD. Check the provider's terms and TLS chain on the actual glasses before releasing. Supply the three properties at build time, for example:

```powershell
.\gradlew.bat :app:assembleDebug '-PtileUrl=https://maps.example.org/street/{z}/{x}/{y}.png' '-PtileCredit=© OpenStreetMap contributors · Example' '-PtileCreditUrl=https://maps.example.org/credits'
```

Gradle properties are compiled into the APK. Provider credentials that must remain secret belong on infrastructure you control.

## Recon and library research

The existing `Recom3Maps` lab project uses Recon GeodataService and earlier map assets. `OsmJet_build`, the Snow2 `Recom3Template`, the installed API-16 Recon SDK add-on, Jet firmware key layouts and Jet input-device reporting provided the build and input references. The old lab project has a separate package identity. This project's requested folder is separate.

- **osmdroid 6.0.1 — Apache License 2.0.** The published AAR declares minSdk 8. The 5.6.5 AAR also declares minSdk 8. The archived 6.1.20 AAR declares minSdk 1, so its manifest alone is weak evidence of compatibility. The app pins and uses the 6.0.1 Web Mercator projection and supplies its own HUD renderer, tile scheduling and HTTP caching. [Upstream project](https://github.com/osmdroid/osmdroid) · [Maven artifact](https://repo.maven.apache.org/maven2/org/osmdroid/osmdroid-android/6.0.1/).
- **Conscrypt 2.5.3 — Apache License 2.0 with bundled BoringSSL notices.** Android 4.1 supports TLS 1.2 but leaves it off by default, and the tested public endpoint rejected the Jet-era cipher suite set. App-local Conscrypt provides a compatible modern cipher. The public OSM host uses the bundled GlobalSign Root R46 CA in an endpoint-specific trust store. Custom HTTPS hosts use platform trust. HTTPS retains standard certificate-chain and hostname verification and refuses redirects. The public endpoint may need a CA update if its certificate chain changes. [Android TLS protocol table](https://developer.android.com/reference/javax/net/ssl/SSLSocket) · [Conscrypt Android 2.5.3 build](https://github.com/google/conscrypt/blob/2.5.3/android/build.gradle) · [GlobalSign root CA](https://support.globalsign.com/ca-certificates/root-certificates/globalsign-root-certificates).
- Jet input reporting showed `ofm_driver` with the four direction keys and Enter. `jet-keys.kl` maps the Jet action key to `BUTTON_1`. The Snow2 template uses D-pad/OK with Enter and controller-button fallbacks. It serves as a mapping reference only.
- GPS and sensors use Android framework APIs. The ReconOS libraries are optional at runtime for direct Wi-Fi or PAN operation.

`Viewport` owns projection and camera movement, `MapHud` draws tiles and position, `HudPosition` owns GPS and heading, and `ViewportTiles` / `TileTransport` handle online tile retrieval through Android HTTPS or `ReconWebBridge`. GPX and turn-by-turn navigation are outside this build.
