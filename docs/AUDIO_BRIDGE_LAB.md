# Audio Bridge Lab

This branch is an isolated experiment. It is intentionally not published to the
production GitHub Release and uses a different Android application id:

`com.cuscus.wifiaudiostreaming.lab`

It can therefore be installed next to the production app without replacing it.

## Experiment 1: adb shell + REMOTE_SUBMIX

Goal: verify that the device's Android shell identity can capture the mixed
system output directly, without MediaProjection, and stream that PCM to an
existing WFAS receiver.

The bridge is implemented by:

`com.cuscus.wifiaudiostreaming.shell.ShellAudioBridgeMain`

It is loaded from the installed lab APK but started by `app_process` inside an
`adb shell`. This means the bridge process should run as Android's shell UID
(2000), not as the APK's normal application UID.

The first prototype deliberately has no discovery beacon, authentication,
encryption or UI integration. It implements only the pieces required by the
existing unicast WFAS client:

- `MODE_PROBE -> UNICAST`
- `HELLO_FROM_CLIENT;v=2 -> HELLO_ACK;v=2`
- one `PING` per second
- WFAS v2 PCM packets with sequence number and sample position
- `CLIENT_BYE`
- same-IP client endpoint refresh after reconnect

### Build

Use the `Build Audio Bridge Lab` GitHub Actions artifact. The workflow does
not publish or overwrite the production release.

### Install on the sending Android device

Install the lab APK normally:

```text
adb install -r WiFi-Audio-Bridge-Lab.apk
```

Then open an interactive shell:

```text
adb shell
```

Inside that shell, resolve the installed APK and launch the bridge:

```sh
APK=$(pm path com.cuscus.wifiaudiostreaming.lab | head -n 1 | cut -d: -f2)
CLASSPATH="$APK" app_process /system/bin \
  com.cuscus.wifiaudiostreaming.shell.ShellAudioBridgeMain \
  --port 9090 \
  --sample-rate 48000 \
  --channels 2 \
  --packet-bytes 512
```

The first line printed should contain:

```text
uid=2000
```

If it does not, stop the test: the bridge is not running with the shell
identity.

### Connect the receiving device

The receiver can be the existing production WFAS app.

1. Put both devices on the same LAN.
2. Set the receiver to 48 kHz / stereo for this first prototype.
3. Enter the sending device's Wi-Fi IP manually.
4. Use port 9090.
5. Connect.

The shell bridge replies to the receiver's `MODE_PROBE`, so manual mode
detection should select unicast.

### Expected first-test behavior

The bridge uses:

`MediaRecorder.AudioSource.REMOTE_SUBMIX`

This is intentionally the same basic capture direction used by scrcpy's direct
system-output path. On some Android builds, REMOTE_SUBMIX redirects matching
audio away from the sending device's local speaker while capture is active.
That is acceptable for this first experiment: the purpose is to prove direct
shell capture first.

Later experiments can try Android 13+ AudioPolicy loopback+render so the source
device continues local playback while a copy is streamed.

### Important results to record

A useful test should answer these questions:

1. Does the bridge print that REMOTE_SUBMIX entered RECORDSTATE_RECORDING?
2. Does the receiver play the sending device's system/media audio?
3. Does the source device still play locally, or is it redirected?
4. Does capture continue with the source screen off?
5. Does it survive 10-30 minutes without stopping?
6. What is the perceived or measured end-to-end latency?
7. Does the receiver reconnect after Wi-Fi is briefly toggled?

### Common failure meaning

If creation/start fails with a permission error while the process really is
UID 2000, the device ROM is restricting REMOTE_SUBMIX beyond the generic shell
behavior. Do not grant privileged capture permissions to the normal APK as a
workaround. The next lab path should be a Shizuku UserService / shell-side
AudioPolicy experiment instead.

If audio is silent but AudioRecord reports RECORDSTATE_RECORDING, verify that
media audio is actually playing and test both stereo and mono. Some vendor
audio policies differ from AOSP/scrcpy behavior.

## Experiment 2: App + Shizuku UserService

This is now the **default internal-audio backend** in the lab app. It is designed
to remove the PC and MediaProjection from day-to-day use. The old MediaProjection
backend is kept only as an explicit compatibility choice in Settings; Shizuku
never silently falls back to screen sharing.

### Target flow

```text
WFAS Lab app
  -> Shizuku
  -> WFAS UserService running as shell uid 2000
  -> system playback capture
  -> WFAS UDP
  -> receiver
```

For Android 13 and newer the UserService uses the same hidden AudioPolicy
direction used by current scrcpy playback capture:

- `AudioMixingRule` targeting players
- `AudioMix`
- `AudioPolicy`
- `ROUTE_FLAG_LOOP_BACK_RENDER`
- `AudioManager.registerAudioPolicyStatic(...)`
- `AudioPolicy.createAudioRecordSink(...)`

`LOOP_BACK_RENDER` is selected specifically so the source device should keep
playing locally while a copy is captured.

Android 11/12 currently use `REMOTE_SUBMIX` as a compatibility fallback. On
those versions local playback may be redirected while capture is active.

### Requirements

- Android 11 or newer.
- Shizuku installed and running.
- For the AudioPolicy path, Shizuku v13 or newer is required.
- Non-root Shizuku is sufficient; when Shizuku was started through ADB/wireless
  debugging the UserService should run as shell uid 2000.
- After a device reboot, non-root Shizuku normally has to be started again.
  Android 11+ can do that on-device through wireless debugging, so a PC is not
  required for normal use.

### Backend selection and current scope

Settings -> Audio sources now exposes an explicit internal-audio backend:

- **Shizuku** — default.
- **Legacy / MediaProjection** — compatibility option only; this is the only
  internal-audio choice allowed to launch Android screen-sharing authorization.

Selecting internal audio with the Shizuku backend always enters the Shizuku
startup path. Unsupported combinations stop with a visible explanation rather
than falling back to MediaProjection.

The first integrated Shizuku sender intentionally supports the path we need to
prove first:

- internal audio
- WFAS unicast
- 48 kHz / stereo / PCM 16-bit for the first proven format
- automatic WFAS discovery
- source phone local playback retained on Android 13+
- foreground host + CPU/Wi-Fi locks
- receiver reconnects can refresh their UDP endpoint

The lab currently requires WFAS security mode `OFF` for this path. Auth,
encryption, microphone mixing, multicast, RTP, HTTP, DLNA and Snapcast are not
yet wired into the privileged bridge. If internal audio and microphone are both
selected, the current session sends internal audio only and shows a warning.
The preference is not silently changed.

The app refuses to pretend unsupported features are protected/supported and
never falls back to MediaProjection automatically.

### How to start it

1. Install the latest `WiFi-Audio-Bridge-Lab` artifact.
2. Install/start Shizuku on the sending phone.
3. Open the lab app and leave the sender in the ordinary internal-audio,
   unicast/WFAS configuration.
4. Tap the normal Start button.
5. Approve the one-time Shizuku permission for the lab app.

For this eligible mode the app does **not** launch MediaProjection and does not
request the screen-capture authorization dialog. The Shizuku UserService starts
the capture and UDP sender directly.

The receiver should discover the sender automatically. Manual IP connection to
the normal WFAS streaming port remains useful as a fallback while this is still
a lab build.

### Settings/UI audit

The lab settings now expose user-facing controls for features that previously
existed only in code:

- internal-audio backend (Shizuku / legacy MediaProjection)
- app language (system / Simplified Chinese / English)
- capture buffer size
- receiver "keep connected" behavior
- direct shortcut to open Shizuku

Simplified Chinese and English string resources are kept at parity. The update
checker and Android release links point to this independent repository rather
than the former upstream Android repository.

Receiver "keep connected" remains enabled by default. When enabled, transport
timeouts and Wi-Fi interruptions trigger recovery/reconnect until the user
explicitly disconnects. It can now be disabled in Settings.

### What still needs device validation

A successful CI build proves that the Shizuku integration and hidden-API
reflection code compile. It does not prove that a particular OEM audio policy
accepts the mix at runtime.

The next useful device validation is therefore:

1. Start from the app without a computer attached.
2. Confirm the UserService reports uid 2000.
3. Confirm no MediaProjection dialog appears.
4. Confirm receiver audio works.
5. On Android 13+, confirm the sending phone still plays locally.
6. Lock both screens and check long-running stability.
7. Measure/compare end-to-end latency.

If AudioPolicy registration fails on a specific ROM, capture the exact
`WFAS_SHIZUKU` / `WFAS_SHIZUKU_APP` log before changing the architecture.

## Isolation guarantees

This lab branch changes the application id and CI workflow. It does not publish
to the production `zh-v1.2.1` release and must not be merged into `main`
until the experiment is proven useful and the diff is reviewed.


### 2026-09-26 Xiaomi/HyperOS compatibility and lab signing

- Android 13+ Shizuku capture now builds AudioPolicy with a shell-identity Context
  (`uid=2000`, package `com.android.shell`) to match scrcpy's privileged playback path.
- Capture fallback order stays entirely inside the Shizuku backend:
  broad AudioPolicy loopback -> scrcpy-compatible MEDIA-only AudioPolicy loopback ->
  REMOTE_SUBMIX compatibility. It never falls back to MediaProjection.
- The REMOTE_SUBMIX fallback may stop local playback on some ROMs; LOOP_BACK_RENDER
  remains the preferred duplication path.
- Lab CI now generates an explicit `.ci/lab-debug.keystore`, caches it under
  `wfas-audio-bridge-lab-debug-keystore-v2`, and passes it to Gradle through
  `WFAS_LAB_KEYSTORE`. After installing the first build signed by this key,
  subsequent active-development builds can upgrade in place instead of requiring
  uninstall/reinstall.
