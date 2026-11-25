# ReconOS SDK hardware test

Tested on 2026-11-16. Recom3 Maps 0.2.0 uses the firmware's public `HUDConnectivityManager.sendWebRequest()` API, the same route found in `Snow2Liftie_build_sdk`. The firmware opens and manages the Bluetooth sessions.

## Results

| Hardware and transport | Result |
| --- | --- |
| Snow2, Huawei P10, Snow3 1.0.2 with the companion patch | **Verified.** The Snow2 ran Android 4.1.2, API 16, ARMv7, firmware increment 16. Both the HUD web and accessory services were registered. The Snow2 had no active Android IP network or IPv4 default route. The Huawei fetched four initial HTTPS tiles from OpenStreetMap and the Snow2 rendered them. |
| Snow2, original Snow3 1.0.2 | The Huawei downloaded the first 13,118-byte HTTPS tile, then blocked on an empty serial output queue. The Snow2 timed out waiting for the reply. |
| Snow2, iPhone | The connected iPhone opened the command, object and file sessions at indices 5–7. The web SDK streams at indices 1–4 were closed. The reference Snow2Liftie SDK build also found an empty SDK output pool. |
| Recon Jet, iPhone | The Jet opened accessory sessions. This Jet's installed ReconOS libraries and HUDServer lack the SDK connectivity service. The app displayed **Recon SDK web unavailable**. Direct Wi-Fi was verified on the Jet in the [initial report](RESULTS.md) and with the test-position build. |

The Huawei test used a temporary copy of Snow3 base commit `5a0a7a2`. Duplicate connection events created competing listeners, and a static field let a web request use another listener's output queue. The [companion patch](snow3-serial-bridge.patch) makes listener startup idempotent and gives each HTTP handler its own transport reference. The existing Snow3 checkout was used read-only. The working test installation was left running on the Huawei, with its original signed APK saved separately for local rollback.

## Jet firmware follow-up (2026-11-25)

The initial Jet result above applies to the older inspected firmware, not all Jet devices. A later session compared two physical Jets whose ReconOS versions were identified by the owner as 4.1 and 4.6. These firmware labels are distinct from the Android 4.1.2 version reported by the older unit; they were not inferred from Android's `ro.build.version.release` property. Device color is not a compatibility test.

| Checked capability | Jet identified as ReconOS 4.1 | Jet identified as ReconOS 4.6 |
| --- | --- | --- |
| `com.reconinstruments.os.HUDOS` | Absent from the inspected system JAR. | Present. |
| `com.reconinstruments.os.connectivity.HUDConnectivityManager` | Absent. | Present. |
| SDK HTTP request/response classes | `HUDHttpRequest` and `HUDHttpResponse` absent. | Both present. |
| Maps phone route | `Recon SDK web service unavailable: NoClassDefFoundError`. | Connectivity service registered; Maps 0.2.1 received live tile responses over iPhone MFi. |
| Recommended current route | Direct Wi-Fi; no SDK retrofit or firmware upgrade was performed. | Phone bridge when the HTTP session is available, or direct Wi-Fi. |

On the later Jet, Bluetooth link loss removed the remote web route. During reconnection the iPhone opened, then closed, the HTTP session while restoring its command/object/file sessions. Maps 0.2.1 issued a tile request before HTTP was available. Its Java timeout expired after fifteen seconds, and subsequent retries waited for the previous SDK request. Restarting the iPhone companion produced `REMOTE_WEB_GAINED`, followed by six tile responses containing data in the same Maps process.

This is evidence of a working phone route and a recovery problem in the existing release, not a successful hardware test of [the recovery patch in PR #2](https://github.com/recom3/recom3-maps/pull/2). Android uses SPP rather than iPhone MFi sessions, and this Jet/iPhone reconnect sequence was not reproduced on Android. The earlier verified Snow2/Android result remains separate.

## Snow2 verification

- The supplied position appeared as **Test position** with a blue marker and heading arrow. The rotation-vector sensor reported heading. A live satellite fix and worn heading calibration remain unverified.
- A force-stopped app relaunch read four Z14 tiles from the SDK disk cache.
- Stopping Snow3 showed cached Z14 tiles and an offline status. Zooming to Z15 showed an uncached viewport. Reconnecting Snow3 fetched the three visible Z15 tiles in the same app process. The first reply arrived about 0.9 seconds after the app selected the SDK route.
- Injected Android D-pad and OK events exercised panning and zoom on the Snow2. Physical button presses remain unverified.
- A host check exercised binary cache storage, freshness and `Age`, `no-cache`, merging validators after a 304, `no-store`, and 12 MiB eviction.
- The Gradle wrapper built the debug APK and lint completed. Android package inspection confirmed API 16, ARMv7 and an optional firmware SDK. APK signature verification passed for Android 4.1.

## Evidence

- [Snow2 live map through the Huawei](snow2-android-sdk-online.png)
- [SDK disk-cache relaunch](snow2-sdk-cache-relaunch.png)
- [Disconnected with cached tiles](snow2-sdk-disconnected-cached.png)
- [Uncached zoom while disconnected](snow2-sdk-disconnected-uncached.png)
- [Live tiles after reconnecting](snow2-sdk-recovered.png) and [panning at Z15](snow2-sdk-panned.png)
- [Paired Snow2 and Huawei logs](snow2-android-sdk-logcat.txt)
- [Unpatched Snow3 and iPhone diagnostic logs](sdk-before-companion-patch.txt)
- [Jet test position over Wi-Fi](jet-test-position-wifi.png), [Jet SDK unavailable over iPhone](jet-iphone-sdk.png), and [Jet app log](jet-sdk-logcat.txt)

For an iPhone companion implementation, the ReconOS SDK expects an app that opens an advertised `com.reconinstruments.hudconnectivity.*` accessory session and serves the Recon SDK network-status and HTTP request/response messages through it. [Apple External Accessory](https://developer.apple.com/documentation/externalaccessory/) · [EASession](https://developer.apple.com/documentation/externalaccessory/easession)

`Recom3-Maps-debug.apk` SHA-256:

```text
79D81BF02F9840713711B6D42AFCC736FAC075754F1BE6E19BE8D631FB1A058A
```
