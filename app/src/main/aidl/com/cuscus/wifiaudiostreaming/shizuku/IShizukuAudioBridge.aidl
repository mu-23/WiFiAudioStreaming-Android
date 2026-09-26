package com.cuscus.wifiaudiostreaming.shizuku;

interface IShizukuAudioBridge {
    void destroy() = 16777114;
    void stopBridge() = 1;
    String startBridge(int port, int sampleRate, int channels, int packetBytes, boolean keepPlayingOnDevice, boolean persistAfterClient) = 2;
    String getStatus() = 3;
    int getBuildVersion() = 4;
    void setVolume(float volume) = 5;
}
