# WiFi Audio Streaming v1.2 简体中文本地化

本修改基于上游 v1.2 源码，仅新增简体中文界面资源，不改动音频传输、网络协议和核心业务逻辑。

## 修改内容

- 新增 `app/src/main/res/values-zh-rCN/strings.xml`
- 覆盖全部 606 个可翻译字符串
- 中文覆盖主页、发送/接收、系统内部音频、设置、权限、USB、VPN、RTP/HTTP、DLNA、Snapcast、二维码配对、自动连接、通知、自动化与脚本、安全与加密、错误提示等
- 保留官方英文和意大利语资源；Android 系统语言为简体中文时会自动使用中文
- 新增 GitHub Actions 自动构建与发布 APK

## 校验结果

- 中文资源 key 覆盖：606 / 606
- 缺失 key：0
- 多余 key：0
- `%s` / `%d` / `%%` 等格式化占位符不匹配：0
- XML 解析：通过

## 构建

GitHub Actions 会在 `zh-v1.2` 分支有相关修改时自动构建 Debug APK，并上传 Artifact，同时创建或更新 `zh-v1.2` Release。

本地构建也可以使用：

```bash
./gradlew :app:assembleDebug
```
