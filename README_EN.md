<div align="center">
  <img src="https://raw.githubusercontent.com/mu-23/WiFiAudioStreaming-Android/main/fastlane/metadata/android/en-US/images/icon.png" alt="WiFi Audio Streaming" width="120" />

  # WiFi Audio Streaming for Android

  **Low-latency Android audio streaming over LAN**

  Stream your Android device's internal audio or microphone audio to another device on the local network, or use Android as the receiving device and play the stream directly.

  **English** | [简体中文](README.md)

  [Download Latest Release](https://github.com/mu-23/WiFiAudioStreaming-Android/releases/latest) ·
  [Releases](https://github.com/mu-23/WiFiAudioStreaming-Android/releases) ·
  [Experimental Branch](https://github.com/mu-23/WiFiAudioStreaming-Android/tree/audio-bridge-lab)
</div>

---

## Project Direction

This repository is now maintained as an **independent Android audio streaming project**. It is no longer focused only on localization of the original project, but on continued development in several areas:

- **Lower LAN audio latency**
- **More reliable long-running connections and automatic recovery**
- **Better behavior during screen-off, Wi-Fi jitter, and temporary network loss**
- **A better Android-to-Android pure-audio streaming experience**
- **Simplified Chinese localization**
- **Non-root system-audio experiments using ADB / Shell / Shizuku**

The primary development branch is **`main`**.

Higher-risk or architecture-level experiments are developed on isolated branches first, for example:

- **`audio-bridge-lab`**: Shell / ADB / Shizuku system-audio bridge experiments

Experimental features are considered for `main` only after they have been validated.

---

## Main Capabilities

### Android Internal Audio Streaming

The app can capture internal audio that Android allows to be captured through the official Playback Capture APIs and stream it to other devices on the local network.

The normal mode **does not require root**.

> Android applies permission and app-level restrictions to playback capture. Some apps can explicitly prevent their audio from being captured by third-party apps.

### Native WFAS Low-Latency Protocol

The project includes WFAS v2 for low-latency PCM audio transport between Android devices.

Current maintenance areas include:

- UDP audio transport
- Packet sequence numbers and sample positions
- Packet-loss and reordering handling
- Playback buffer control
- PLC / gap concealment
- Connection liveness detection
- Automatic reconnect
- Network-change recovery
- Optional authentication and encryption

For new installations, the default WFAS Wi-Fi latency setting is currently **40 ms**. Lower-latency adaptive buffering is still under active development.

### Multiple Output Protocols

In addition to WFAS, the project retains a broad set of output options:

- **RTP**: for VLC, FFplay, Kodi, and other compatible players
- **HTTP**: browser-based playback
- **DLNA / UPnP**: TVs, receivers, speakers, and other renderers
- **Snapcast**: synchronized multi-room playback

Different protocols serve different goals:

| Protocol | Primary Use |
| --- | --- |
| WFAS | Android ↔ Android, prioritizing low latency |
| RTP | General media-player compatibility |
| HTTP | Convenient browser playback |
| DLNA | TVs, receivers, speakers, and appliances |
| Snapcast | Synchronized playback across multiple devices |

---

## Stability Improvements

The current `main` branch includes several changes aimed at long-running real-world use.

### More Reliable Liveness Detection

The WFAS unicast client no longer relies only on PING packets to decide whether the server is alive.

As long as valid audio is still being received and successfully validated, the server is treated as active. This avoids false disconnects such as:

```text
Audio is still arriving normally
↓
A few PING packets are lost due to Wi-Fi jitter
↓
The client disconnects by mistake
```

### Keep Wi-Fi and CPU Active During Screen-Off

While receiving a stream, the client uses:

- Android `PARTIAL_WAKE_LOCK`
- Wi-Fi high-performance / low-latency locks

This is intended to reduce audio jitter and disconnects caused by aggressive power saving after the screen turns off.

### Automatic Recovery from Temporary Disconnects

Manually started client sessions now retry automatically after transient network failures.

The current retry backoff is approximately:

```text
1s → 2s → 3s → 5s → 10s
```

A short Wi-Fi interruption no longer necessarily means the user has to reconnect manually.

### Connection Diagnostics

More detailed disconnect diagnostics are now logged, including:

- Last PING activity
- Last audio activity
- Overall server activity
- Network revision
- Disconnect reason
- Link metrics

This helps distinguish actual UDP inactivity, a server-initiated shutdown, a local stop, and a network transition.

---

## Direct System-Audio Experiments

In addition to Android's official Playback Capture route, the project is experimenting with a more direct architecture:

```text
Android system mix
        ↓
Shell / Shizuku audio bridge
        ↓
PCM
        ↓
WFAS UDP
        ↓
Another Android device
```

Goals:

- No root
- Reduce or avoid reliance on MediaProjection where possible
- Obtain system-mix PCM more directly
- Reuse the existing WFAS network and playback pipeline

Current experimental code is available on:

[`audio-bridge-lab`](https://github.com/mu-23/WiFiAudioStreaming-Android/tree/audio-bridge-lab)

The first stage is validating `REMOTE_SUBMIX` capture under the Android Shell UID. Later stages will continue with Shizuku UserService and Android AudioPolicy experiments.

**The experimental branch does not overwrite production releases and does not directly modify `main`.**

---

## Simplified Chinese

This repository includes complete Simplified Chinese localization and continues to maintain the Chinese UI.

Production APKs use a persistent signing key so future versions signed with the same key can upgrade in place.

---

## Security and Encryption

WFAS supports optional connection authentication and encryption:

- Off
- Ask
- Key
- HMAC-SHA256 challenge-response
- ChaCha20-Poly1305
- HKDF-SHA256

These features are mainly intended as lightweight privacy protection on trusted local networks.

They should not be treated as a dedicated secure transport for hostile networks, high-threat environments, or security-critical deployments.

Protocol details:

[`WFAS_PROTOCOL.md`](WFAS_PROTOCOL.md)

---

## Quick Start

### Sender

1. Open the app.
2. Go to **Send / Server**.
3. Enable **Internal Audio**.
4. Select WFAS.
5. Choose unicast or multicast as needed.
6. Start the server.

Android will request permission for internal audio capture.

Only audio is captured here; the app does not encode and transmit the screen image.

### Receiver

1. Open the app on another Android device.
2. Go to **Receive / Client**.
3. Wait for the sender to be discovered.
4. Tap the device to connect.

If discovery is blocked on the LAN, the sender IP can also be entered manually.

---

## Building

Android Studio / JDK 17 is recommended.

```bash
git clone https://github.com/mu-23/WiFiAudioStreaming-Android.git
cd WiFiAudioStreaming-Android
./gradlew assembleDebug
```

Main technologies:

- Kotlin
- Jetpack Compose
- Material 3
- Coroutines / StateFlow
- Ktor Networking
- Android AudioRecord / AudioTrack
- MediaCodec
- Bouncy Castle

---

## Branches

| Branch | Purpose |
| --- | --- |
| `main` | Current maintained release branch |
| `master` | Preserved upstream baseline |
| `audio-bridge-lab` | ADB / Shell / Shizuku audio-bridge experiments |
| `zh-v1.2` | Early Chinese development branch retained for history |

Production development follows `main`.

---

## Project Origin and Modification Notice

This repository was originally based on Marco Morosi's
[WiFiAudioStreaming-Android](https://github.com/marcomorosi06/WiFiAudioStreaming-Android).

The original project provided substantial foundations including Android audio capture, WFAS, RTP, HTTP, DLNA, Snapcast, and the Compose UI architecture.

**Since September 2026, this repository has undergone continued modification and is maintained as an independent repository.**

Subsequent changes include, but are not limited to:

- Simplified Chinese localization
- Build and release workflow changes
- Client disconnect diagnostics
- Valid audio traffic participating in liveness detection
- CPU / Wi-Fi keep-awake behavior during streaming
- Automatic recovery from temporary disconnects
- Lower default WFAS latency
- Direct system-audio experiments and further low-latency architecture research

Copyright and license notices from the original project remain preserved.

This notice is also intended to make clear that this repository is a modified derivative work and is not an official release by the original author.

---

## License

This project continues to be distributed under the **European Union Public Licence v1.2 (EUPL v1.2)**.

Full license text:

[`LICENSE.md`](LICENSE.md)

Under the EUPL requirements:

- Existing copyright and license notices are retained
- Modified versions continue under the applicable license terms
- This repository clearly identifies that the work has been modified and when independent maintenance began
- Third-party components continue to use their respective licenses

Third-party notices:

[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)

---

## Credits

Thanks to the original project author **Marco Morosi** and contributors for the foundation this project was built on.

Thanks also to the Android, Kotlin, Jetpack Compose, Ktor, Bouncy Castle, and other open-source communities used by this project.

The project continues to evolve around one core goal:

> **Make system-audio streaming between Android devices simpler, more reliable, and as low-latency as practical.**
