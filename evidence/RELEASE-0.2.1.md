# Recom3 Maps 0.2.1 verification

Date: 2026-11-23.

## Changes

- Version 0.2.1, versionCode 3; package remains `com.recom3.maps`.
- Launcher icon now shows a folded map and cyan location marker.
- PNG icon resources are supplied for mdpi, hdpi, xhdpi, xxhdpi and xxxhdpi. The matching 256 x 256 catalog icon is in `artwork/recom3_maps_icon.png`.
- Map functionality and dependencies are unchanged.

## Validation

- Release build and release lint passed with zero errors and eight warnings: three existing legacy warnings and five IconLauncherShape design warnings for the opaque square icon backgrounds.
- Visual review at 48 x 48 pixels passed. Each of the five icon images packaged in the APK was extracted and compared against its source; all pixel comparisons matched.
- APK metadata confirms version 0.2.1 (3), minimum and target API 16, and ARMv7 support.
- Release debugging and testOnly flags are disabled. The original Maps signing identity is retained and APK signature verification passed for API 16.
- Updated the connected Recon Jet from 0.2.0 to 0.2.1 without uninstalling. The installed package reports versionCode 3 with debugging disabled. A cold application launch succeeded and the HUD rendered OpenStreetMap tiles.
- No new GPS fix was obtained during this check. Snow2 was not retested for this update. The USB connection ended before the new Maps icon could be selected in the launcher; the packaged icon resources were verified as described above.
- Local RIF validation passed. The final distributable is `dist/Recom3-Maps.rif` (1,309,888 bytes), SHA-256 `F00352C44428B895A67D7FA393C5621C7F9967699407ED837354F796CF3E8D21`.

The RIF is not committed or uploaded. Proprietary packaging tools and procedures are outside this PR.
