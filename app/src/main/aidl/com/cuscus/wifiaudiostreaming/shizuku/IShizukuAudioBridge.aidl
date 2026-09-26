package com.cuscus.wifiaudiostreaming.shizuku;

interface IShizukuAudioBridge {
    void destroy() = 16777114;
    void stopBridge() = 1;
    String startBridge(int port, int sampleRate, int channels, int packetBytes, boolean keepPlayingOnDevice) = 2;
    String getStatus() = 3;
}
