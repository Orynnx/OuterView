# OuterView

OuterView 3.0.0 is an independent LSPosed module and Compose manager for custom
Xiaomi rear-display Smart Assistant cards and wallpapers.

The 3.0 runtime does not depend on DexKit, MMKV, or GPL/LGPL/AGPL libraries. It
uses a project-owned host-query layer backed by Google's BSD-licensed
`smali-dexlib2`. New data uses the `org.orynnx.outerview.core` namespace and
`outerview_custom_` identifiers; the former identifier is accepted only for
controlled migration of records created by older OuterView releases.

## Quick start

1. Install the APK and enable OuterView in LSPosed.
2. Scope it to `com.xiaomi.subscreencenter`, then restart that process or reboot.
3. Verify the Assistant and Wallpaper host connections in OuterView.
4. Import a trusted card ZIP and enable it when installation completes.

The ready-to-import [Hello Card](demo/hello-card/hello-card.zip) contains only
project-authored XML and metadata, with no external media.

## Build

JDK 17 and Android SDK 36 are required.

```bash
./gradlew :core:testDebugUnitTest :app:assembleDebug
python3 demo/hello-card/build_card.py --check
```

The current tree is licensed under the [MIT License](LICENSE). Releases and
commits from the 2.x line remain under their original GPL-3.0 terms. See
[LICENSE_TRANSITION.md](docs/LICENSE_TRANSITION.md) for the provenance and
repeatable audit, and [third-party notices](LICENSES/NOTICE.md) for permissive
runtime dependency licenses.
