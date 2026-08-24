# Third-party notices

OuterView itself is MIT licensed. Its distributed APK contains the following
runtime dependency families, all under permissive licenses:

| Component family | Selected license |
| --- | --- |
| AndroidX, Jetpack Compose, Material Components, Android Gradle generated support | Apache-2.0 |
| Kotlin standard library and kotlinx Coroutines/Serialization | Apache-2.0 |
| YukiHookAPI 1.3.1 | Apache-2.0 |
| KavaRef 1.0.2 | Apache-2.0 |
| BetterAndroid (transitive from YukiHookAPI) | Apache-2.0 |
| Gson 2.14.0 | Apache-2.0 |
| Guava and its failureaccess/listenablefuture support | Apache-2.0 |
| Error Prone and J2ObjC annotations | Apache-2.0 |
| JSpecify annotations | Apache-2.0 |
| Android Hidden API Bypass 6.1 | Apache-2.0 |
| smali-dexlib2 3.0.9 | BSD-3-Clause and Apache-2.0 portions; see `SMALI.txt` |
| SLF4J API 2.0.17 | MIT; see `SLF4J-MIT.txt` |
| JSR-305 annotations 3.0.2 | Apache-2.0 |
| Checker Framework qualifier annotations | MIT |
| JetBrains annotations | Apache-2.0 |
| Gradle Wrapper 9.6.1 (source/build tooling only) | Apache-2.0 |

The complete Apache License 2.0 text is in `Apache-2.0.txt`. Exact selected
runtime coordinates can be reproduced with:

```powershell
.\tools\verify-runtime-licenses.ps1
```

`de.robv.android.xposed:api:82` and YukiHookAPI's KSP processor are build-only
dependencies and are not packaged into the APK; both are Apache-2.0 licensed.
The standard `gradlew`, `gradlew.bat`, and `gradle-wrapper.jar` files are
upstream Gradle build tooling under Apache-2.0 and are likewise not APK runtime
dependencies.

Xiaomi, HyperOS, Smart Assistant, and MAML are names associated with their
respective owners. OuterView is an independent community project and is not
affiliated with or endorsed by Xiaomi.
