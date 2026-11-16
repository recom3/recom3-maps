# Recon Jet hardware test

Tested on 2026-11-16 using the attached physical Recon Jet. The device reports model `JET`, Android 4.1.2, API 16 and `armeabi-v7a`. Screenshots were captured from the Jet's framebuffer at 428 × 240.

## Confirmed

| Check | Observation |
| --- | --- |
| Build and install | The Gradle wrapper built the debug APK. APK inspection confirmed package `com.recom3.maps`, min/target API 16, ARMv7 and v1/v2 signatures. ADB installed and launched the APK on the Jet. |
| Live online map and TLS | Android's active route was Wi-Fi on a conventional access point. The Jet fetched and displayed OSM raster tiles over verified TLS. The log reports HTTP 200 and `TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256`. |
| Pan and zoom | ADB-injected D-pad moved the viewport. ADB-injected OK entered zoom mode and the right key changed Z4 to Z5. Screenshots capture the movement and state changes. |
| Heading | The device's rotation-vector sensor fed the magnetic heading readout. The heading calibration and alignment on worn glasses remain unverified. |
| Cache and cache misses | With Wi-Fi off, the app process was stopped and relaunched. All six visible Z4 tiles were read from the private HTTP disk cache. Zooming to uncached Z5 showed the offline state and blank map. |
| Recovery | After restoring Wi-Fi, the same app process downloaded the Z5 tiles without another keypress or relaunch. The first HTTPS tile arrived about 1.6 seconds after Android reported Wi-Fi. |
| GPS provider | Android showed an active request to the GPS provider from the app. The HUD displayed **GPS searching** throughout the bench run. |

`assembleDebug` and `lintDebug` passed. Lint reported four warnings for the legacy target API, the API-23 cleartext manifest attribute, Google App Indexing and the programmatically constructed HUD view. The tile client itself explicitly requires HTTPS on API 16.

Debug APK SHA-256:

```text
D70C5317FFB5A6F8525C5146624239CC0C2C73A03893CC78B301C44DA3EC08F9
```

## Evidence

- [Online HUD](jet-online.png) and [after panning](jet-panned.png)
- [Offline relaunch with cached tiles](jet-offline-cached-relaunch.png)
- [Uncached zoom while offline](jet-offline-uncached.png)
- [Same viewport after reconnecting](jet-reconnected.png)
- [App log from the offline and recovery session](jet-logcat.txt)

## Pending hardware checks

- **Phone tethering.** The verified Wi-Fi route was a conventional access point. Phone hotspot and Bluetooth PAN still need verification. Connect the Jet to the phone, confirm that the phone lists it as a tethered client, and load a fresh zoom level to verify an HTTPS request.
- **GPS marker and follow.** The provider registered successfully. A real fix, the position marker, and GPS follow require a powered-on Jet with a clear view of the sky.
- **Physical input.** The Jet exposed its optical D-pad/Enter and `BUTTON_1` action key in the hardware key layouts. The same Android actions were exercised through ADB. Physical optical swipes and rocker presses still need hands-on verification.
- **Recon Snow2.** No Snow2 was connected. Its launcher, controls, GPS, TLS and available tethering routes are unverified.
