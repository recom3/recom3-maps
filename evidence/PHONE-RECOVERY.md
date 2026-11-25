# Phone web recovery

## Observed failure

The previous release returned live tiles through a Jet's iPhone MFi HTTP channel. After Bluetooth link loss, the companion reopened the web session briefly, closed it, and restored the command/object/file sessions without restoring the web route. Maps treated accessory connectivity as sufficient and submitted a tile request. Its fifteen-second timeout interrupted the Java future, but the firmware Binder call remained blocked. Later retries reported that the previous SDK request was still running. Restarting the iPhone companion restored the HTTP route and tile responses.

Android uses SPP rather than iPhone MFi sessions. The same failure was not reproduced on Android. Both routes use the firmware's public web SDK from Maps. Older Jet firmware without that SDK continues to require direct Wi-Fi; this change does not add a legacy serial protocol or update firmware.

## Change

- Require both a connected accessory and `hasWebConnection()` before selecting the phone route; recheck before each remote HTTP request.
- Run SDK access in an app-private helper process. Keep SDK connection-event handling on its own Looper, independent of the thread performing synchronous HTTP calls.
- Bound helper probes and HTTP calls. Retire a failed helper, stop its process, and reject old replies before starting a replacement. An interrupted Binder call cannot occupy Maps' request worker indefinitely.
- Keep the direct Wi-Fi route, HTTP status handling, validators, binary tile checks and disk cache behavior.

## Validation

The `phoneWebRegression` host task uses fake channels. It covers connection/web readiness combinations, ordinary replies, binary body and validator preservation, a never-returning request followed by recovery, helper death, old-session responses, interruption, closing a pending request and request-ID filtering.

Validation on 2026-11-25 passed 38 host checks, Debug and Release assembly, and Debug and Release lint. Lint reported zero errors and the existing eight warnings. The debug APK passed signature verification for API 16 and declares version 0.2.2/code 4, min/target API 16 and ARMv7. Release assembly produces an unsigned candidate; no distribution package or deployment was produced for this PR.

The corrected build has not yet been installed on the physical Jet or Snow2. The observed device logs describe the original failure and recovery, not validation of this patch. A hardware check should interrupt an active iPhone download, restore the companion HTTP session, and verify fresh tiles in the same Maps process. Repeat on Android SPP and verify direct Wi-Fi on firmware without the SDK.
