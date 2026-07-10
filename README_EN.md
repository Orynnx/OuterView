# OuterView

OuterView is an independent LSPosed module and Compose manager for custom Xiaomi rear-display Smart Assistant cards. It targets Xiaomi 17 Pro / 17 Pro Max and does not require REAREye.

It validates and installs `Widget version="2"` ZIP packages, then activates cards through the host Smart Assistant runtime without posting Android notifications.

## Quick start

1. Install the APK and enable OuterView in LSPosed.
2. Scope the module to `com.xiaomi.subscreencenter`.
3. restart the host process or reboot the device.
4. Open OuterView and verify that Host API v3 is connected.
5. Import a card ZIP. Installation is automatic; visibility remains off by default.
6. Use the switch to show or hide the card, and the overflow menu to replace, diagnose, or delete it.

The ready-to-import [Dino Run demo](demo/dino-run/dino-run.zip) is included.

## Build

JDK 17 and Android SDK 37 are required.

```bash
./gradlew :core:testDebugUnitTest :app:assembleDebug
```

For API integration, read [DEVELOPMENT.md](docs/DEVELOPMENT.md). For card packaging and adaptation, read [CARD_DEVELOPMENT.md](docs/CARD_DEVELOPMENT.md).

OuterView is licensed under GPL-3.0. Demo media attribution is documented separately in [DINO-ASSETS.md](LICENSES/DINO-ASSETS.md).
