/*
 * Experimental Shizuku audio bridge for the audio-bridge-lab branch.
 *
 * Copyright (c) 2026 Marco Morosi and contributors
 * Licensed under the EUPL, Version 1.2 or later versions approved by the EC.
 */

package com.cuscus.wifiaudiostreaming.shizuku;

import com.cuscus.wifiaudiostreaming.BuildConfig;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Keep;

import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shizuku UserService. This code is loaded from our APK, but the process itself
 * runs with Shizuku's privilege (normally Android shell uid 2000).
 *
 * Android 13+ uses the same AudioPolicy loopback+render direction as scrcpy's
 * playback capture: audio keeps rendering on the source device while a copy is
 * exposed through an AudioRecord sink. Android 11/12 keep REMOTE_SUBMIX only as
 * a compatibility fallback, where local playback may be redirected.
 */
public final class ShizukuAudioBridgeService extends IShizukuAudioBridge.Stub {

    private static final String TAG = "WFAS_SHIZUKU";
    private static final int SHELL_UID = 2000;
    private static final int PROTOCOL_VERSION = 2;
    private static final int HEADER_SIZE = 10;
    private static final byte MAGIC_0 = 0x57;
    private static final byte MAGIC_1 = 0x46;

    private final Context context;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String status = "idle";
    private volatile Thread bridgeThread;
    private volatile DatagramSocket socket;
    private volatile AudioRecord recorder;
    private volatile Object registeredAudioPolicy;
    private volatile Class<?> registeredAudioPolicyClass;
    private volatile String activeCaptureMode = "none";

    public ShizukuAudioBridgeService() {
        this.context = null;
        Log.i(TAG, "constructed without Context uid=" + Process.myUid());
    }

    @Keep
    public ShizukuAudioBridgeService(Context context) {
        this.context = context;
        Log.i(TAG, "constructed with Context uid=" + Process.myUid() + " context=" + context);
    }

    @Override
    public synchronized String startBridge(
            int port,
            int sampleRate,
            int channels,
            int packetBytes,
            boolean keepPlayingOnDevice,
            boolean persistAfterClient
    ) {
        stopBridgeInternal();

        int uid = Process.myUid();
        if (uid != SHELL_UID && uid != 0) {
            status = "error: UserService uid=" + uid + " (expected shell 2000)";
            return status;
        }
        if (port < 1024 || port > 65535) {
            status = "error: invalid port " + port;
            return status;
        }
        if (channels != 1 && channels != 2) {
            status = "error: channels must be 1 or 2";
            return status;
        }
        if (sampleRate < 8000 || sampleRate > 192000) {
            status = "error: invalid sample rate " + sampleRate;
            return status;
        }

        int frameSize = channels * 2;
        int safePacketBytes = Math.max(128, Math.min(packetBytes, 1390));
        safePacketBytes -= safePacketBytes % frameSize;
        if (safePacketBytes < frameSize) safePacketBytes = frameSize;

        String captureMode;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (context == null) {
                    throw new IllegalStateException("Shizuku v13+ Context is required for AudioPolicy");
                }
                try {
                    recorder = createPlaybackCapture(sampleRate, channels, keepPlayingOnDevice);
                    captureMode = activeCaptureMode;
                } catch (Throwable policyFailure) {
                    Log.w(TAG, "AudioPolicy playback capture failed, trying REMOTE_SUBMIX fallback", policyFailure);
                    releaseCapture();
                    recorder = createRemoteSubmixCapture(sampleRate, channels, safePacketBytes);
                    captureMode = "REMOTE_SUBMIX fallback";
                    activeCaptureMode = captureMode;
                }
            } else if (Build.VERSION.SDK_INT >= 30) {
                recorder = createRemoteSubmixCapture(sampleRate, channels, safePacketBytes);
                captureMode = "REMOTE_SUBMIX compatibility";
                activeCaptureMode = captureMode;
            } else {
                throw new UnsupportedOperationException("system audio requires Android 11+");
            }

            if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord is not initialized");
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord did not enter RECORDSTATE_RECORDING");
            }
        } catch (Throwable t) {
            releaseCapture();
            status = "error: build=" + BuildConfig.VERSION_CODE + " " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            Log.e(TAG, "capture start failed", t);
            return status;
        }

        running.set(true);
        final int finalPacketBytes = safePacketBytes;
        bridgeThread = new Thread(
                () -> runServer(port, sampleRate, channels, finalPacketBytes, persistAfterClient),
                "wfas-shizuku-bridge"
        );
        bridgeThread.setDaemon(true);
        bridgeThread.start();

        status = "running build=" + BuildConfig.VERSION_CODE + " uid=" + uid +
                " mode=" + captureMode +
                " port=" + port +
                " " + sampleRate + "Hz/" + channels + "ch" +
                " packet=" + finalPacketBytes + "B" +
                " persist=" + persistAfterClient;
        Log.i(TAG, status);
        return status;
    }

    @Override
    public synchronized void stopBridge() {
        stopBridgeInternal();
    }

    @Override
    public String getStatus() {
        return status;
    }

    @Override
    public int getBuildVersion() {
        return BuildConfig.VERSION_CODE;
    }


    @Override
    public void destroy() {
        stopBridgeInternal();
        Log.i(TAG, "destroy");
        System.exit(0);
    }

    private synchronized void stopBridgeInternal() {
        running.set(false);

        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }

        Thread t = bridgeThread;
        bridgeThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        releaseCapture();
        status = "idle";
    }

    private void runServer(
            int port,
            int sampleRate,
            int channels,
            int packetBytes,
            boolean persistAfterClient
    ) {
        DatagramSocket localSocket = null;
        try {
            localSocket = new DatagramSocket(null);
            localSocket.setReuseAddress(true);
            localSocket.setReceiveBufferSize(1 << 20);
            localSocket.setSendBufferSize(1 << 20);
            localSocket.setSoTimeout(1000);
            localSocket.bind(new InetSocketAddress(port));
            socket = localSocket;
            Log.i(TAG, "listening on UDP :" + port);

            while (running.get()) {
                InetSocketAddress client = waitForClient(localSocket);
                if (client == null || !running.get()) break;
                Log.i(TAG, "client connected " + client);
                boolean cleanClientBye =
                        runSession(localSocket, client, sampleRate, channels, packetBytes);
                if (running.get() && cleanClientBye && !persistAfterClient) {
                    Log.i(TAG, "client disconnected cleanly; persistence disabled, stopping bridge");
                    status = "idle";
                    running.set(false);
                    break;
                }
                if (running.get()) {
                    Log.i(TAG, cleanClientBye
                            ? "client disconnected cleanly; waiting for next client"
                            : "client heartbeat lost; keeping server alive for reconnect");
                }
            }
        } catch (SocketException e) {
            if (running.get()) {
                status = "error: UDP " + e.getMessage();
                Log.e(TAG, "UDP bridge failed", e);
            }
        } catch (Throwable t) {
            if (running.get()) {
                status = "error: " + t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.e(TAG, "bridge failed", t);
            }
        } finally {
            running.set(false);
            if (localSocket != null) {
                try {
                    localSocket.close();
                } catch (Throwable ignored) {
                }
            }
            if (socket == localSocket) socket = null;
            releaseCapture();
        }
    }

    private InetSocketAddress waitForClient(DatagramSocket s) throws Exception {
        byte[] buf = new byte[2048];
        while (running.get()) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                s.receive(packet);
            } catch (SocketTimeoutException ignored) {
                continue;
            }

            InetSocketAddress remote = new InetSocketAddress(packet.getAddress(), packet.getPort());
            String text = new String(packet.getData(), packet.getOffset(), packet.getLength()).trim();

            if ("MODE_PROBE".equals(text)) {
                sendText(s, remote, "UNICAST");
            } else if (text.startsWith("HELLO_FROM_CLIENT")) {
                int version = tokenInt(text, "v", 0);
                if (version != PROTOCOL_VERSION) {
                    sendText(s, remote, "WFAS_INCOMPATIBLE;v=" + PROTOCOL_VERSION);
                    continue;
                }
                sendText(s, remote, "HELLO_ACK;v=" + PROTOCOL_VERSION);
                return remote;
            }
        }
        return null;
    }

    private boolean runSession(
            DatagramSocket s,
            InetSocketAddress initialClient,
            int sampleRate,
            int channels,
            int packetBytes
    ) throws Exception {
        final int frameSize = channels * 2;
        final AtomicBoolean sessionAlive = new AtomicBoolean(true);
        final AtomicBoolean explicitClientBye = new AtomicBoolean(false);
        final AtomicBoolean pongCapable = new AtomicBoolean(false);
        final AtomicLong lastClientActivityAt = new AtomicLong(System.currentTimeMillis());
        final AtomicReference<InetSocketAddress> client = new AtomicReference<>(initialClient);
        final InetAddress clientIp = initialClient.getAddress();

        Thread controlThread = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (running.get() && sessionAlive.get()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(packet);
                } catch (SocketTimeoutException ignored) {
                    continue;
                } catch (Throwable t) {
                    if (running.get() && sessionAlive.get()) {
                        Log.w(TAG, "control receive failed: " + t.getMessage());
                    }
                    break;
                }

                InetSocketAddress remote = new InetSocketAddress(packet.getAddress(), packet.getPort());
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength()).trim();

                try {
                    if ("MODE_PROBE".equals(text)) {
                        sendText(s, remote, "UNICAST");
                    } else if (text.startsWith("HELLO_FROM_CLIENT")) {
                        int version = tokenInt(text, "v", 0);
                        if (version != PROTOCOL_VERSION) {
                            sendText(s, remote, "WFAS_INCOMPATIBLE;v=" + PROTOCOL_VERSION);
                        } else if (remote.getAddress().equals(clientIp)) {
                            client.set(remote);
                            lastClientActivityAt.set(System.currentTimeMillis());
                            sendText(s, remote, "HELLO_ACK;v=" + PROTOCOL_VERSION);
                            Log.i(TAG, "client endpoint refreshed " + remote);
                        } else {
                            sendText(s, remote, "WFAS_BUSY");
                        }
                    } else if ("PONG".equals(text) && remote.getAddress().equals(clientIp)) {
                        client.set(remote);
                        pongCapable.set(true);
                        lastClientActivityAt.set(System.currentTimeMillis());
                    } else if ("CLIENT_BYE".equals(text) && remote.getAddress().equals(clientIp)) {
                        lastClientActivityAt.set(System.currentTimeMillis());
                        explicitClientBye.set(true);
                        sessionAlive.set(false);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "control reply failed: " + t.getMessage());
                }
            }
        }, "wfas-shizuku-control");
        controlThread.setDaemon(true);
        controlThread.start();

        Thread pingThread = new Thread(() -> {
            while (running.get() && sessionAlive.get()) {
                try {
                    Thread.sleep(1000);
                    sendText(s, client.get(), "PING");
                    if (pongCapable.get() &&
                            System.currentTimeMillis() - lastClientActivityAt.get() > 6_000L) {
                        Log.w(TAG, "client heartbeat timed out; releasing session");
                        sessionAlive.set(false);
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    if (running.get()) Log.w(TAG, "PING failed: " + t.getMessage());
                }
            }
        }, "wfas-shizuku-ping");
        pingThread.setDaemon(true);
        pingThread.start();

        byte[] readBuffer = new byte[Math.max(packetBytes, 4096)];
        int seq = 0;
        long samplePosition = 0;
        long packets = 0;

        try {
            while (running.get() && sessionAlive.get()) {
                AudioRecord r = recorder;
                if (r == null) break;

                int read = r.read(
                        readBuffer,
                        0,
                        readBuffer.length,
                        AudioRecord.READ_NON_BLOCKING
                );
                if (read < 0) {
                    throw new IllegalStateException("AudioRecord.read failed: " + read);
                }
                if (read == 0) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                int alignedRead = read - (read % frameSize);
                int offset = 0;
                while (offset < alignedRead && running.get() && sessionAlive.get()) {
                    int chunk = Math.min(packetBytes, alignedRead - offset);
                    chunk -= chunk % frameSize;
                    if (chunk <= 0) break;

                    byte[] out = new byte[HEADER_SIZE + chunk];
                    out[0] = MAGIC_0;
                    out[1] = MAGIC_1;
                    out[2] = (byte) PROTOCOL_VERSION;
                    out[3] = 0;
                    out[4] = (byte) ((seq >>> 8) & 0xFF);
                    out[5] = (byte) (seq & 0xFF);
                    ByteBuffer.wrap(out, 6, 4)
                            .order(ByteOrder.BIG_ENDIAN)
                            .putInt((int) (samplePosition & 0xFFFFFFFFL));
                    System.arraycopy(readBuffer, offset, out, HEADER_SIZE, chunk);

                    InetSocketAddress target = client.get();
                    s.send(new DatagramPacket(out, out.length, target.getAddress(), target.getPort()));

                    seq = (seq + 1) & 0xFFFF;
                    samplePosition += chunk / frameSize;
                    offset += chunk;
                    packets++;

                    if (packets == 1 || packets % 2000 == 0) {
                        Log.i(TAG, "sent packets=" + packets + " seq=" + seq + " target=" + target);
                    }
                }
            }
        } finally {
            sessionAlive.set(false);
            controlThread.interrupt();
            pingThread.interrupt();
            try {
                controlThread.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                pingThread.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return explicitClientBye.get();
    }

    @SuppressLint({"PrivateApi", "WrongConstant", "MissingPermission"})
    private AudioRecord createPlaybackCapture(
            int sampleRate,
            int channels,
            boolean keepPlayingOnDevice
    ) throws Exception {
        Throwable broadFailure;
        try {
            AudioRecord record = createAudioPolicyCapture(
                    sampleRate,
                    channels,
                    keepPlayingOnDevice,
                    new int[] {
                            AudioAttributes.USAGE_MEDIA,
                            AudioAttributes.USAGE_GAME,
                            AudioAttributes.USAGE_UNKNOWN
                    },
                    true
            );
            activeCaptureMode = keepPlayingOnDevice
                    ? "AudioPolicy LOOP_BACK_RENDER"
                    : "AudioPolicy LOOP_BACK";
            return record;
        } catch (Throwable t) {
            broadFailure = t;
            Log.w(TAG, "Broad AudioPolicy mix failed, retrying scrcpy-compatible MEDIA-only mix", t);
        }

        try {
            AudioRecord record = createAudioPolicyCapture(
                    sampleRate,
                    channels,
                    keepPlayingOnDevice,
                    new int[] { AudioAttributes.USAGE_MEDIA },
                    false
            );
            activeCaptureMode = keepPlayingOnDevice
                    ? "AudioPolicy scrcpy-compat LOOP_BACK_RENDER"
                    : "AudioPolicy scrcpy-compat LOOP_BACK";
            return record;
        } catch (Throwable t) {
            IllegalStateException combined = new IllegalStateException(
                    "AudioPolicy capture failed; broad=" +
                            broadFailure.getClass().getSimpleName() + ": " +
                            String.valueOf(broadFailure.getMessage()) +
                            "; scrcpy-compat=" +
                            t.getClass().getSimpleName() + ": " +
                            String.valueOf(t.getMessage()),
                    t
            );
            combined.addSuppressed(broadFailure);
            throw combined;
        }
    }

    @SuppressLint({"PrivateApi", "WrongConstant", "MissingPermission"})
    private AudioRecord createAudioPolicyCapture(
            int sampleRate,
            int channels,
            boolean keepPlayingOnDevice,
            int[] usages,
            boolean enableVoiceCaptureBeforeBuild
    ) throws Exception {
        Class<?> mixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> mixingRuleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");

        Object mixingRuleBuilder = mixingRuleBuilderClass.getConstructor().newInstance();

        int mixRolePlayers = mixingRuleClass.getField("MIX_ROLE_PLAYERS").getInt(null);
        mixingRuleBuilderClass
                .getMethod("setTargetMixRole", int.class)
                .invoke(mixingRuleBuilder, mixRolePlayers);

        if (enableVoiceCaptureBeforeBuild) {
            try {
                mixingRuleBuilderClass
                        .getMethod("voiceCommunicationCaptureAllowed", boolean.class)
                        .invoke(mixingRuleBuilder, true);
            } catch (Throwable ignored) {
                // Optional. MEDIA/GAME capture must not depend on this OEM-specific path.
            }
        }

        int ruleMatchUsage = mixingRuleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null);
        Method addMixRule = mixingRuleBuilderClass.getMethod("addMixRule", int.class, Object.class);
        for (int usage : usages) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(usage)
                    .build();
            addMixRule.invoke(mixingRuleBuilder, ruleMatchUsage, attributes);
        }

        Object mixingRule = mixingRuleBuilderClass.getMethod("build").invoke(mixingRuleBuilder);

        // scrcpy invokes this after build(). Keep the MEDIA-only retry as close as
        // possible to upstream behavior without letting this optional method fail.
        if (!enableVoiceCaptureBeforeBuild) {
            try {
                mixingRuleBuilderClass
                        .getMethod("voiceCommunicationCaptureAllowed", boolean.class)
                        .invoke(mixingRuleBuilder, true);
            } catch (Throwable ignored) {
            }
        }

        Class<?> audioMixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> audioMixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object audioMixBuilder = audioMixBuilderClass
                .getConstructor(mixingRuleClass)
                .newInstance(mixingRule);

        int channelMask = channels == 2
                ? AudioFormat.CHANNEL_IN_STEREO
                : AudioFormat.CHANNEL_IN_MONO;
        AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build();

        audioMixBuilderClass
                .getMethod("setFormat", AudioFormat.class)
                .invoke(audioMixBuilder, audioFormat);

        String routeFlagName = keepPlayingOnDevice
                ? "ROUTE_FLAG_LOOP_BACK_RENDER"
                : "ROUTE_FLAG_LOOP_BACK";
        int routeFlags = audioMixClass.getField(routeFlagName).getInt(null);
        audioMixBuilderClass
                .getMethod("setRouteFlags", int.class)
                .invoke(audioMixBuilder, routeFlags);

        Object audioMix = audioMixBuilderClass.getMethod("build").invoke(audioMixBuilder);

        Class<?> audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> audioPolicyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");

        Context policyContext = createShellAudioContext(context);
        Object audioPolicyBuilder = audioPolicyBuilderClass
                .getConstructor(Context.class)
                .newInstance(policyContext);
        audioPolicyBuilderClass
                .getMethod("addMix", audioMixClass)
                .invoke(audioPolicyBuilder, audioMix);
        Object audioPolicy = audioPolicyBuilderClass.getMethod("build").invoke(audioPolicyBuilder);

        Method register = AudioManager.class
                .getDeclaredMethod("registerAudioPolicyStatic", audioPolicyClass);
        register.setAccessible(true);
        int result = (Integer) register.invoke(null, audioPolicy);
        if (result != 0) {
            throw new IllegalStateException("registerAudioPolicyStatic returned " + result);
        }

        AudioRecord resultRecord = null;
        try {
            Method createSink = audioPolicyClass.getMethod("createAudioRecordSink", audioMixClass);
            resultRecord = (AudioRecord) createSink.invoke(audioPolicy, audioMix);
            if (resultRecord == null) {
                throw new IllegalStateException("createAudioRecordSink returned null");
            }
            if (resultRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException(
                        "createAudioRecordSink returned uninitialized AudioRecord state=" +
                                resultRecord.getState()
                );
            }

            registeredAudioPolicy = audioPolicy;
            registeredAudioPolicyClass = audioPolicyClass;
            return resultRecord;
        } catch (Throwable t) {
            if (resultRecord != null) {
                try {
                    resultRecord.release();
                } catch (Throwable ignored) {
                }
            }
            unregisterPolicy(audioPolicy, audioPolicyClass);
            throw t;
        }
    }

    @SuppressLint({"WrongConstant", "MissingPermission"})
    private AudioRecord createRemoteSubmixCapture(
            int sampleRate,
            int channels,
            int packetBytes
    ) {
        int channelMask = channels == 2
                ? AudioFormat.CHANNEL_IN_STEREO
                : AudioFormat.CHANNEL_IN_MONO;
        int minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuffer <= 0) {
            throw new IllegalStateException("unsupported AudioRecord format: " + minBuffer);
        }

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build();

        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(minBuffer, packetBytes * 8));

        if (Build.VERSION.SDK_INT >= 31 && context != null) {
            builder.setContext(createShellAudioContext(context));
        }

        AudioRecord record = builder.build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            try {
                record.release();
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("REMOTE_SUBMIX AudioRecord is not initialized");
        }
        return record;
    }

    private static Context createShellAudioContext(Context base) {
        if (base == null || Build.VERSION.SDK_INT < 31) {
            return base;
        }
        return new ShellAudioContext(base);
    }

    @TargetApi(31)
    private static final class ShellAudioContext extends ContextWrapper {
        ShellAudioContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return "com.android.shell";
        }

        @Override
        public String getOpPackageName() {
            return "com.android.shell";
        }

        @Override
        public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(SHELL_UID)
                    .setPackageName("com.android.shell")
                    .build();
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Context createPackageContext(String packageName, int flags) {
            return this;
        }
    }

    private synchronized void releaseCapture() {
        AudioRecord r = recorder;
        recorder = null;
        if (r != null) {
            try {
                r.stop();
            } catch (Throwable ignored) {
            }
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }

        Object policy = registeredAudioPolicy;
        Class<?> policyClass = registeredAudioPolicyClass;
        registeredAudioPolicy = null;
        registeredAudioPolicyClass = null;
        if (policy != null && policyClass != null) {
            unregisterPolicy(policy, policyClass);
        }
    }

    private static void unregisterPolicy(Object policy, Class<?> policyClass) {
        try {
            Method unregister = AudioManager.class.getDeclaredMethod(
                    "unregisterAudioPolicyAsyncStatic",
                    policyClass
            );
            unregister.setAccessible(true);
            unregister.invoke(null, policy);
        } catch (Throwable t) {
            Log.w(TAG, "could not unregister AudioPolicy: " + t.getMessage());
        }
    }

    private static void sendText(
            DatagramSocket socket,
            InetSocketAddress remote,
            String text
    ) throws Exception {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(
                bytes,
                bytes.length,
                remote.getAddress(),
                remote.getPort()
        ));
    }

    private static int tokenInt(String message, String name, int fallback) {
        String prefix = name + "=";
        String[] parts = message.split(";");
        for (String part : parts) {
            if (part.startsWith(prefix)) {
                try {
                    return Integer.parseInt(part.substring(prefix.length()));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
