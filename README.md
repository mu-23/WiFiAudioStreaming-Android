<div align="center">
  <img src="https://raw.githubusercontent.com/mu-23/WiFiAudioStreaming-Android/main/fastlane/metadata/android/en-US/images/icon.png" alt="WiFi Audio Streaming" width="120" />

  # WiFi Audio Streaming for Android

  **Android 局域网低延迟音频串流**

  将 Android 设备的内部音频或麦克风音频实时发送到局域网中的另一台设备，也可以把 Android 设备作为接收端直接播放音频。

  [下载最新版本](https://github.com/mu-23/WiFiAudioStreaming-Android/releases/latest) ·
  [查看 Releases](https://github.com/mu-23/WiFiAudioStreaming-Android/releases) ·
  [实验分支](https://github.com/mu-23/WiFiAudioStreaming-Android/tree/audio-bridge-lab)
</div>

---

## 项目定位

这个仓库目前作为一个**独立维护的 Android 音频串流项目**继续开发，重点不再只是原项目的简单汉化，而是围绕下面几个方向持续改进：

- **更低的局域网音频延迟**
- **更稳定的长时间连接与自动恢复**
- **息屏、Wi-Fi 抖动、短暂断网后的持续工作能力**
- **Android → Android 的纯音频传输体验**
- **简体中文本地化**
- **不 Root 的系统音频直出实验（ADB / Shell / Shizuku）**

当前正式开发分支为 **`main`**。

高风险或架构级实验会优先放在独立分支，例如：

- **`audio-bridge-lab`**：Shell / ADB / Shizuku 系统音频桥实验

实验功能验证稳定后才会考虑合并进 `main`。

---

## 当前主要能力

### Android 内部音频串流

支持通过 Android 官方 Playback Capture 能力捕获允许被录制的内部音频，并通过局域网发送到其他设备。

普通模式**不需要 Root**。

> Android 对系统音频捕获有权限和应用级限制。部分应用可以禁止自己的音频被第三方应用捕获。

### WFAS 原生低延迟协议

项目内置 WFAS v2，用于 Android 设备之间的低延迟 PCM 音频传输。

当前维护方向包括：

- UDP 音频传输
- 包序号与采样位置
- 丢包与乱序处理
- 播放缓冲控制
- PLC / 音频缺口掩盖
- 连接存活检测
- 自动重连
- 网络变化恢复
- 可选认证与加密

当前新安装默认 WFAS Wi-Fi 延迟设置为 **40 ms**，后续仍在继续研究更低延迟的自适应缓冲方案。

### 多种输出协议

除了 WFAS，还保留了原项目丰富的输出能力：

- **RTP**：可供 VLC、FFplay、Kodi 等播放器使用
- **HTTP**：浏览器直接播放
- **DLNA / UPnP**：推送到电视、功放、音箱等设备
- **Snapcast**：多房间同步音频

这些协议的目标不同：

| 协议 | 主要用途 |
| --- | --- |
| WFAS | Android ↔ Android，优先低延迟 |
| RTP | 通用播放器兼容 |
| HTTP | 最方便的浏览器播放 |
| DLNA | 电视、功放、音箱等家电 |
| Snapcast | 多设备同步播放 |

---

## 稳定性改进

当前 `main` 已加入多项针对实际长时间使用的改进。

### 更合理的连接存活判断

WFAS 单播客户端不再只依赖 PING 判断服务器是否存活。

只要客户端持续收到并成功校验有效音频，就会认为服务器仍然在线，从而避免：

```text
音频其实还在正常传输
↓
连续几个 PING 因 Wi-Fi 抖动丢失
↓
客户端被错误断开
```

### 息屏期间保持 Wi-Fi 和 CPU 工作

客户端串流期间会使用：

- Android `PARTIAL_WAKE_LOCK`
- Wi-Fi 高性能 / 低延迟锁

用于降低设备息屏后 Wi-Fi 省电策略导致的音频抖动和连接中断。

### 临时断线自动重连

手动建立的客户端会话在遇到临时网络问题后自动尝试重新连接。

当前退避节奏大致为：

```text
1s → 2s → 3s → 5s → 10s
```

短暂 Wi-Fi 波动不再意味着用户必须重新手动点连接。

### 连接诊断

项目增加了更详细的断线诊断信息，包括：

- PING 最后活动时间
- 音频最后活动时间
- 总服务器活动时间
- 网络 revision
- 断开原因
- 链路统计

方便区分真正的 UDP 中断、服务器主动结束、客户端停止和网络切换。

---

## 系统音频直出实验

除了 Android 官方 Playback Capture 路线，目前还在研究一种更直接的方案：

```text
Android 系统混音
        ↓
Shell / Shizuku 音频桥
        ↓
PCM
        ↓
WFAS UDP
        ↓
另一台 Android
```

目标是：

- 不 Root
- 尽量不依赖 MediaProjection
- 直接获得系统混音 PCM
- 与现有 WFAS 接收端共用同一套网络与播放链路

当前实验代码位于：

[`audio-bridge-lab`](https://github.com/mu-23/WiFiAudioStreaming-Android/tree/audio-bridge-lab)

第一阶段正在验证 Android Shell UID 下的 `REMOTE_SUBMIX` 音频捕获；后续会继续研究 Shizuku UserService 和 Android AudioPolicy 路线。

**实验分支不会覆盖正式 Release，也不会直接修改 `main`。**

---

## 简体中文

本仓库已加入完整的简体中文本地化，并持续针对中文界面进行维护。

正式 APK 使用固定签名构建，后续同一签名版本可以直接覆盖升级。

---

## 安全与加密

WFAS 支持可选的连接认证和加密：

- Off
- Ask
- Key
- HMAC-SHA256 challenge-response
- ChaCha20-Poly1305
- HKDF-SHA256

这些功能主要面向可信局域网中的轻量隐私保护。

它们不应被当作高威胁环境、敌对网络或安全关键场景中的专用安全传输方案。

协议细节见：

[`WFAS_PROTOCOL.md`](WFAS_PROTOCOL.md)

---

## 快速开始

### 发送端

1. 打开应用。
2. 进入 **发送 / Server**。
3. 启用 **内部音频**。
4. 选择 WFAS。
5. 根据需要选择单播或组播。
6. 启动服务器。

Android 会要求内部音频捕获授权。

这里只采集音频，不会把屏幕画面编码并通过网络发送。

### 接收端

1. 打开另一台 Android 设备上的应用。
2. 进入 **接收 / Client**。
3. 等待自动发现发送端。
4. 点击设备连接。

如果局域网屏蔽了发现广播，也可以手动填写发送端 IP。

---

## 构建

需要 Android Studio / JDK 17。

```bash
git clone https://github.com/mu-23/WiFiAudioStreaming-Android.git
cd WiFiAudioStreaming-Android
./gradlew assembleDebug
```

主要技术栈：

- Kotlin
- Jetpack Compose
- Material 3
- Coroutines / StateFlow
- Ktor Networking
- Android AudioRecord / AudioTrack
- MediaCodec
- Bouncy Castle

---

## 分支说明

| 分支 | 用途 |
| --- | --- |
| `main` | 当前正式维护版本 |
| `master` | 保留的原始上游基线 |
| `audio-bridge-lab` | ADB / Shell / Shizuku 音频桥实验 |
| `zh-v1.2` | 早期中文开发分支，历史保留 |

正式版本开发以 `main` 为准。

---

## 项目来源与修改声明

本仓库最初基于 Marco Morosi 的
[WiFiAudioStreaming-Android](https://github.com/marcomorosi06/WiFiAudioStreaming-Android)
继续开发。

原项目为本仓库提供了大量基础实现，包括 Android 音频采集、WFAS、RTP、HTTP、DLNA、Snapcast、Compose UI 等核心框架。

**自 2026 年 9 月起，本仓库在原始代码基础上进行了持续修改并作为独立仓库维护。**

当前仓库的后续修改包括但不限于：

- 简体中文本地化
- 构建与发布流程调整
- 客户端断线诊断
- 有效音频参与连接存活判断
- 息屏期间 CPU / Wi-Fi 保活
- 临时断线自动重连
- 更低的默认 WFAS 延迟
- 系统音频直出实验及后续低延迟架构研究

原项目及其作者的版权和许可声明继续保留。

这份说明同时用于明确标识本仓库属于经过修改的衍生作品，而不是原作者发布的官方版本。

---

## License

本项目继续遵循 **European Union Public Licence v1.2 (EUPL v1.2)**。

完整许可文本见：

[`LICENSE.md`](LICENSE.md)

根据 EUPL 的要求：

- 保留原有版权与许可声明
- 修改后的作品继续按照相应许可条件发布
- 本仓库明确标识了衍生修改及修改时间
- 第三方组件仍分别遵循其自己的许可证

第三方依赖和版权信息见：

[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md)

---

## 致谢

感谢原项目作者 **Marco Morosi** 及原项目贡献者提供的基础实现。

同时感谢 Android、Kotlin、Jetpack Compose、Ktor、Bouncy Castle 及项目所依赖的其他开源软件社区。

本仓库后续会继续围绕一个核心目标演进：

> **让 Android 设备之间的系统音频传输更简单、更稳定，并尽可能降低实际端到端延迟。**
