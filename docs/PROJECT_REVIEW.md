# 项目优化建议

审查日期：2026-10-17。范围包括播放捕获、RAOP/DLNA 传输、设备发现、服务生命周期及相关测试。下面 8 项是审查发现，尚未作为本次本机静音兼容改动的一部分修复。

## 1. 优先：避免元数据请求阻塞音频发送

位置：`RaopStreamingClient.sendTrackMetadata()`、`sendTrackArtwork()`、`sendProgress()` 和 `writePcm()`。

这些方法共用对象锁，元数据发送持锁等待 RTSP 响应，默认读取超时为 5 秒。音频队列只有 32 KiB，在 44.1 kHz、16-bit 双声道下约为 186 ms；发送被阻塞时队列会丢弃旧 PCM，可能产生播放跳跃。该影响由调用链推导，尚未做真机卡顿复现。

建议让 RTSP 控制请求独立串行执行，只在读取或更新会话状态时短暂加锁，并保留 FLUSH 与音频状态更新的顺序约束。补充“元数据响应延迟时仍持续发送音频”的并发测试。

## 2. 优先：将服务销毁时的阻塞清理移出主线程

位置：`StreamingService.onDestroy()`、`stopSession()`。

`onDestroy()` 直接进入完整清理流程，涉及发送线程等待、录音线程等待、DACP 线程等待，以及 DLNA 停播网络请求。异常或慢设备场景下，这些等待会累计占用主线程。

建议主线程先使当前会话失效并中止活动 I/O，将有界等待及协议善后交给清理线程；确保清理仍能在服务工作线程关闭后完成。补充销毁与连接、停止并发的生命周期测试。

## 3. 修复录音格式校验失败时的资源释放缺口

位置：`PlaybackCapture.buildAudioRecord()`，尤其是 `validateAudioRecordFormat(record)`。

本地 `AudioRecord` 创建后，如果格式校验抛异常，方法没有释放该对象。由于方法尚未返回，外层 `createdRecord = buildAudioRecord()` 的赋值没有完成，外层失败清理也无法取得这个录音器。

建议在构建方法内部保证所有“已创建但未交付”的录音器在异常时释放，并测试格式不符和读取格式失败两种路径。

## 4. 提前拒绝无关 DACP 来源，限制整个请求的读取时间

位置：`DacpControlServer.acceptLoop()`、`handleConnection()`、`dispatch()`。

服务串行读取完整 HTTP 请求后才检查来源地址。无关来源发送半个请求即可占用接收线程，延迟合法接收器的播放、暂停命令。现有 socket 读取超时是空闲超时，不能限制持续缓慢发送的整个请求。

本地 socket 探针已复现：无关来源发送一个 `G` 后，合法暂停请求在 400 ms 内未得到响应；关闭前一连接后，合法请求立即得到 HTTP 204，控制回调执行。

建议在 accept 后立即检查来源地址，并给整个请求设置截止时间。现有完整请求鉴权测试不能覆盖这一阻塞场景，需要补充半开连接并发测试。

## 5. 让 DLNA 扫描的截止时间和取消覆盖 HTTP 请求

位置：`DlnaDiscovery.receiveDiscoveryPacket()`、`fetchDescription()`、`cancelScan()`。

UDP 接收循环内同步下载设备 XML 并查询能力；4 秒扫描截止时间只在循环头检查。取消扫描仅关闭 UDP socket，不能结束正在执行的 HTTP 请求，下一轮扫描也会排在同一执行器中等待。

建议设备描述与能力查询共享剩余时间预算，取消时中断活动 HTTP；将可选能力查询延后，或使用有上限的并发任务。补充慢 HTTP、取消后立即重扫的真实扫描测试；当前截止时间测试只覆盖状态对象。

## 6. 按 XML 层级解析嵌套 DLNA 设备

位置：`DlnaDeviceDescriptionParser.findRenderer()`、`text()`、`serviceType()` 和 `serviceControlUri()`。

递归搜索后代节点会把外层 Basic 容器识别成播放器，然后组合外层名称、UDN 与内层播放器的控制地址，影响列表显示和设备去重。

本地 Java 探针已复现：给现有嵌套测试的外层设备补充独立名称和 UDN，返回结果使用外层身份，AVTransport 却来自内部播放器。现有测试的外层没有身份字段，未暴露该问题。

建议身份字段只读取当前设备的直接子节点，服务只读取直接所属的 `serviceList`，并显式遍历嵌套设备。保留当前对厂商自定义设备类型的兼容行为。

## 7. 减少音频发送热路径上的分配与复制

位置：`AsyncPcmSender.offer()`、`AlacPacketBuilder`、`RtpPacketizer`、`RaopStreamingClient` 的重传缓存、`RaopCrypto.encryptAudioPayload()`。

当前链路会复制入队 PCM、创建 ALAC 和 RTP 数组、克隆重传缓存；加密模式还为每个包创建 Cipher 和结果数组。UDP 在 44.1 kHz 下约每秒发送 125 个音频包，这些分配持续发生。尚未进行分配率和 GC 性能采样，不能认定已经造成卡顿。

建议先测量，再优先让 ALAC 直接写入 RTP 目标缓冲区，明确重传包所有权以减少复制，并复用发送线程专属加密器。必须保留每包 IV 重置、重传缓存不被后续写入覆盖的语义，并运行已有加密、重传和 FFmpeg 解码回归测试。

## 8. 对封面编码做变化检测和缓存

位置：`StreamingService.onMetadataChanged()`、`encodeArtwork()`。

播放、暂停等状态变化也会触发元数据回调；服务每次都先缩放并 JPEG 编码封面，之后才在发送策略中去重。因此相同封面可能重复消耗 CPU 并分配位图和字节数组，即使接收器不支持封面也会进行编码。

建议在编码前检查封面是否变化和接收器是否需要封面，按会话缓存已编码结果，并明确缓存和临时缩略图的生命周期。补充“同一封面仅切换播放状态时不重复编码”的测试。

## 本机静音兼容处理

用户报告 OPPO Find X8 Ultra 上投送后媒体音量没有降到 0。原实现只设置一次音量索引，未读回验证。本次改动增加结果检查、一次显式静音兼容尝试、失败提示及对应的恢复逻辑。

两种系统接口都可能被系统音量策略拒绝，因此这属于有验证和失败反馈的兼容处理，并非已确认解决该机型固件问题。[Android AudioManager 官方接口说明](https://developer.android.com/reference/android/media/AudioManager)。仍需在目标机验证本机静音、接收端持续有声、用户调整音量及停止后的恢复行为。

本次修改复用已有 `DeviceMutePolicy`，将平台音量调用集中到服务适配器，由新增的纯 Java `DeviceMuteController` 检查结果和管理恢复。恢复在同一次清理中最多尝试两次，避免依赖服务销毁后的异步任务；不新增权限或依赖，也不持续强制修改用户音量。同步更新设置行为说明和失败提示。

验证结果：

- 标准 Gradle 流程的 219 项单元测试全部通过，其中包含 22 项新增静音控制器测试。
- Debug 和 Release 的 Android lint 均通过，无错误；各有一项现有 JUnit 版本升级提示。
- `assembleRelease` 和 `bundleRelease` 均成功，离线构建复用 Android SDK 36 随附的 AAPT2。
- Release APK 和 AAB 的签名验证通过，证书与此前签名的 Release 包一致；APK 的 zipalign 和非调试模式检查通过。产物为 `app/build/outputs/apk/release/app-release.apk` 和 `app/build/outputs/bundle/release/app-release.aab`。
- 代码复审通过。尚未进行 OPPO Find X8 Ultra 及接收端音频的真机验证。
