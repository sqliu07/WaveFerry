<p align="center">
  <img src="docs/assets/waveferry-logo.svg" alt="WaveFerry 波形传输应用图标" width="120">
</p>

<h1 align="center">声渡 / WaveFerry</h1>

声渡（WaveFerry）是一款面向 Android 12 及以上设备的局域网音频投送应用。它读取用户授权应用正在播放的音频，并投送到同一局域网内的 AirPlay 1 / RAOP 或 DLNA 接收器。

## 功能

- 通过 DNS-SD 发现 `_raop._tcp`、`_airplay._tcp` 接收器，并通过 SSDP 发现 DLNA/UPnP MediaRenderer。
- 支持 AirPlay 1 / RAOP 的 TCP 与 UDP 传输，以及 `et=0` 和 `et=1` 会话。
- 支持 DLNA 的 PCM/WAV 实时投送。
- RAOP 可在 44.1 kHz 或 48 kHz、双声道、PCM 16-bit 间按接收器能力协商；DLNA 实时投送固定为 44.1 kHz、双声道、PCM 16-bit。
- 将歌曲标题、艺人、专辑、封面和播放进度同步到兼容接收器。
- 兼容接收器可反向控制手机播放：RAOP 使用 DACP 支持播放、暂停、上一首和下一首；DLNA 可处理播放/暂停反馈。
- 可选在投送时静音本机媒体音量，并在会话结束后恢复。
- 支持英文和简体中文，跟随系统语言；Android 13 及以上可在系统应用语言设置中切换。
- 在“设置 → 关于”查看当前版本。GitHub 版本支持检查更新、下载 APK、查看进度及交给系统确认安装；Google Play 构建打开商店更新页面。
- 提供项目和隐私说明入口。

## 兼容条件

| 项目 | 要求 |
| --- | --- |
| Android | Android 12（API 31）或更高版本 |
| 网络 | 手机与接收器处于同一 Wi-Fi / 局域网 |
| 音频源 | 源应用允许 Android Playback Capture |
| 接收器 | 旧式 AirPlay 1 / RAOP，或支持 PCM/WAV 的 DLNA/UPnP MediaRenderer |
| 音频格式 | RAOP：44.1/48 kHz PCM 16-bit 双声道；DLNA：44.1 kHz PCM/WAV 16-bit 双声道 |
| 元数据与反向控制 | 接收器实现相应协议能力，且手机已授予通知使用权 |

Android 14 及以上会在每次投送前显示系统级捕获对话框，让用户选择一个应用。声渡只创建音频录制器，不采集或传输屏幕画面。

## 使用方法

1. 安装并打开声渡，确保手机与接收器位于同一局域网。
2. 从设备列表选择 AirPlay 或 DLNA 设备；没有找到时点击“扫描”。
3. 点击“开始”。应用先连接并准备接收器会话。
4. 接收器连接成功后，在系统捕获对话框中选择要投送音频的应用，完成第二阶段的播放捕获授权。
5. 在所选应用中开始播放。连接状态可从声渡的常驻通知或主界面查看；点击“停止”可结束会话。
6. 投送期间如需换设备，可展开主界面的设备列表，选择另一台接收器并点击“切换设备”。

通知使用权不是基础投送的必需条件，但当前歌曲展示、播放状态同步和接收器反向控制依赖它。

开启“投送时静音本机媒体音量”后，应用会检查静音是否生效。若系统未允许自动静音，投送仍会继续，主界面和投送通知会提示手动调低本机媒体音量。

## 当前不支持

- 仅支持 AirPlay 2 / FairPlay 的设备。
- HomePod、新式配对、PIN、密码或 Digest 认证。
- 视频或屏幕镜像、多房间同步、接收端音量控制。
- Chromecast 或 Google Cast。
- 不允许 Playback Capture 的音频源应用。

## 隐私

声渡不包含账号、广告、分析 SDK 或遥测功能。音频、歌曲信息和封面只在设备内存中处理，并按用户操作发送到所选局域网接收器。GitHub 版本仅在用户主动检查或下载更新时访问 GitHub，下载由系统管理器执行；Google Play 构建将更新交给商店。应用不会向这些服务发送音频、歌曲信息或局域网设备列表。投送期间用于接收器播放控制的临时局域网服务只接受当前所选接收器的命令。AirPlay 1 与 DLNA 的局域网传输没有端到端加密，请仅在可信网络中使用。完整说明见 [PRIVACY.md](PRIVACY.md)。

## 第三方声明

RAOP 实现参考了 PulseAudio 的 RAOP 客户端。相关声明与 LGPL-2.1-or-later 文本见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和 [LICENSES/LGPL-2.1-or-later.txt](LICENSES/LGPL-2.1-or-later.txt)。

## 许可证

WaveFerry 以 [MIT License](LICENSE) 开源。第三方组件仍适用其各自的许可证。
