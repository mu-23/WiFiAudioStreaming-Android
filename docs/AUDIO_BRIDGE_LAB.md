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

## Isolation guarantees

This lab branch changes the application id and CI workflow. It does not publish
to the production `zh-v1.2.1` release and must not be merged into `main`
until the experiment is proven useful and the diff is reviewed.
