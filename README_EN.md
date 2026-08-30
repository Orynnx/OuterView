# OuterView

OuterView 2.4.0 is an independent LSPosed module and Compose manager for custom
Xiaomi rear-display Smart Assistant cards and wallpapers.

The 2.4 runtime does not depend on DexKit, MMKV, or third-party GPL/LGPL/AGPL libraries. It
uses a project-owned host-query layer backed by Google's BSD-licensed
`smali-dexlib2`. New data uses the `org.orynnx.outerview.core` namespace and
`outerview_custom_` identifiers; the former identifier is accepted only for
controlled migration of records created by older OuterView releases.

Current version: `2.4.0`; Assistant Host API: `v5`; Wallpaper Host API: `v3`.

## Quick start

1. Install the APK and enable OuterView in LSPosed.
2. Scope it to `com.xiaomi.subscreencenter`, then restart that process or reboot.
3. Verify the Assistant and Wallpaper host connections in OuterView.
4. Import a trusted card ZIP and enable it when installation completes.

The ready-to-import [Hello Card](demo/hello-card/hello-card.zip) contains only
project-authored XML and metadata, with no external media.

Card imports scan every XML entry and surface executable, reflective,
external-data, and system-control capabilities for explicit confirmation.
Wallpaper imports reject those capabilities outright. This scan is not a
sandbox; import packages only from sources you trust.

## Build

JDK 17 and Android SDK 37 are required.

```bash
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :core:lintDebug :app:lintDebug :app:assembleDebug :app:assembleRelease
python3 demo/hello-card/build_card.py --check
```

The current tree and newly distributed source and APKs are licensed under
[GNU GPL v3.0](LICENSE). Copies obtained before the license transition remain
under the terms granted with those copies. See
[LICENSE_TRANSITION.md](docs/LICENSE_TRANSITION.md) for the provenance and
repeatable audit, and [third-party notices](LICENSES/NOTICE.md) for permissive
runtime dependency licenses.
