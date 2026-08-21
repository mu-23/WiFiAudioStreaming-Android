# Third-Party Software & Licenses — WiFi Audio Streaming (Android)

This application is licensed under the **EUPL v1.2** (see `LICENSE.md`).
It uses the third-party open-source components listed below. Each remains the
property of its respective authors and is distributed under its own licence.
We are grateful to all of these projects.

> **WFAS v2 protocol (MIT):** the WiFi Audio Streaming wire protocol is this
> project's own. A C reference implementation is published separately under the
> **MIT License** (Copyright © 2026 Marco Morosi,
> <https://github.com/marcomorosi06/wfas-protocol>) so anyone can adopt WFAS v2 freely,
> e.g. on microcontrollers. It is not bundled in this app.

---

## Summary table

| Component | Version | Licence | Copyright |
|---|---|---|---|
| AndroidX Core KTX, Activity, Activity Compose | (per BOM / pinned) | Apache License 2.0 | The Android Open Source Project / Google LLC |
| AndroidX Lifecycle (runtime-ktx, viewmodel-compose) | 2.8.x | Apache License 2.0 | The Android Open Source Project / Google LLC |
| Jetpack Compose (UI, Graphics, Tooling, Material 3) | via Compose BOM | Apache License 2.0 | The Android Open Source Project / Google LLC |
| Compose Material Icons Extended | 1.5.0 | Apache License 2.0 | The Android Open Source Project / Google LLC |
| AndroidX DataStore Preferences | 1.0.0 | Apache License 2.0 | The Android Open Source Project / Google LLC |
| AndroidX Glance (App Widget, Material 3) | 1.1.0 | Apache License 2.0 | The Android Open Source Project / Google LLC |
| AndroidX Core SplashScreen | 1.0.1 | Apache License 2.0 | The Android Open Source Project / Google LLC |
| Kotlin standard library | (Kotlin plugin) | Apache License 2.0 | JetBrains s.r.o. |
| kotlinx.coroutines (Android) | 1.8.0 | Apache License 2.0 | JetBrains s.r.o. |
| Ktor (client-core, client-cio, server-core, server-cio, network) | 2.3.11 | Apache License 2.0 | JetBrains s.r.o. |
| ZXing Core | 3.5.3 | Apache License 2.0 | ZXing authors |
| zxing-cpp (Android wrapper + native library) | 2.3.0 | Apache License 2.0 | Axel Waggershauser and contributors |
| AndroidX CameraX (core, camera2, lifecycle, view) | 1.4.2 | Apache License 2.0 | The Android Open Source Project |
| AndroidX Graphics Shapes | 1.0.1 | Apache License 2.0 | The Android Open Source Project |
| AndroidX Security Crypto | 1.1.0 | Apache License 2.0 | The Android Open Source Project |
| AndroidX ProfileInstaller | 1.4.1 | Apache License 2.0 | The Android Open Source Project |
| Bouncy Castle (`bcprov-jdk18on`) | 1.78.1 | Bouncy Castle Licence (MIT-style) | The Legion of the Bouncy Castle Inc. |
| JUnit 4 *(test only, not shipped)* | — | Eclipse Public License 1.0 | JUnit contributors |
| AndroidX Test, Espresso *(test only, not shipped)* | — | Apache License 2.0 | The Android Open Source Project |

Versions reflect `app/build.gradle.kts` and the version catalog at the time of
writing; transitive dependencies inherit the licence of their project.

---

## Apache License 2.0

The following components are licensed under the Apache License, Version 2.0
(<https://www.apache.org/licenses/LICENSE-2.0>):

* AndroidX libraries and Jetpack Compose — © The Android Open Source Project / Google LLC
* AndroidX CameraX and Graphics Shapes — © The Android Open Source Project
* Kotlin standard library and kotlinx.coroutines — © JetBrains s.r.o.
* Ktor — © JetBrains s.r.o.
* ZXing Core — © ZXing authors (<https://github.com/zxing/zxing>)
* zxing-cpp — © Axel Waggershauser and contributors (<https://github.com/zxing-cpp/zxing-cpp>)

A full copy of the Apache License 2.0 is available at the URL above. `NOTICE`
files shipped by these projects are preserved in their respective artifacts.

---

## QR codes: encoding and decoding

Two distinct libraries, both Apache 2.0:

* **ZXing Core** (`com.google.zxing:core`, pure Java) **generates** the pairing
  QR codes shown on screen.
* **zxing-cpp** (`io.github.zxing-cpp:android`) **decodes** the QR codes read
  from the camera. It is the C++ port of ZXing, shipped as an AAR containing
  the `libzxingcpp_android.so` native library built from the sources at
  <https://github.com/zxing-cpp/zxing-cpp> — no prebuilt third-party blob and
  no proprietary component. Decoding is entirely on-device: no frame, image or
  decoded value is sent anywhere.

This application contains **no proprietary dependency** and no Google Play
Services / ML Kit component; it runs unchanged on devices without Google
services.

---

## Bouncy Castle Licence

Bouncy Castle (`bcprov-jdk18on` 1.78.1) is
© 2000–2024 The Legion of the Bouncy Castle Inc. (<https://www.bouncycastle.org>)
and is distributed under an MIT-style licence:

```
Copyright (c) 2000–2024 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. ...
```

---

## How to regenerate this list

The authoritative source is `app/build.gradle.kts` and `gradle/libs.versions.toml`.
To dump the full resolved dependency tree run:

```
./gradlew app:dependencies --configuration releaseRuntimeClasspath
```
