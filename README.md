# TVCast — Android TV DLNA 投屏接收端

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE) [![Release](https://img.shields.io/github/v/release/jjh-ai-coder/TVCast)](https://github.com/jjh-ai-coder/TVCast/releases)

[English](README.en.md)

Android TV/盒子上的 DLNA/UPnP MediaRenderer（DMR）接收端。手机端任意支持 DLNA 投屏的 App（BubbleUPnP、B站、爱奇艺、腾讯视频、系统相册等）在局域网内发现电视后，即可向大屏推送视频/音乐/图片。

> 设计文档：`docs/superpowers/specs/2026-10-22-tv-dlna-receiver-design.md` ｜ 逐任务计划：`docs/superpowers/plans/2026-10-22-tv-dlna-receiver-plan.md`

## 构建与验证

- 版本组合：AGP 8.13.2 ｜ Kotlin 2.2.21 ｜ Gradle 9.3.1 ｜ compileSdk 34 / minSdk 21 / targetSdk 34 ｜ Media3 ExoPlayer 1.4.1 ｜ Glide 4.16.0
- 构建 APK：`./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`（本机已验证构建成功）
- 单元测试：`./gradlew :app:testDebugUnitTest`（本机已验证 **75/75 全绿**，含 fake 控制点端到端回归）
- 也可直接用 Android Studio 打开工程（SDK 路径见 `local.properties`）

## 运行时验证（已通过）

- ✅ **真机实测（2026-10-22）**：雷鸟 65 英寸电视安装 v1.0.0 APK，**B站客户端**与**手机系统相册**投屏均正常——手机可发现设备（SSDP 组播通路），视频/图片推送后自动起播
- ✅ 前台服务以 mediaPlayback 类型合规运行；HTTP 栈 80 → 高位端口回落符合设计
- ✅ 待机页显示设备名 `TVCast-XXXX` 与 `http://IP:端口`
- ✅ GET /dd.xml、SCPD、GetProtocolInfo 全部正常
- ✅ SUBSCRIBE → SEQ:0 初始快照；**隐式播放**（仅 SetAVTransportURI）→ 真实视频起播（RelTime 持续推进、画面确认渲染）
- ✅ Pause → GENA NOTIFY 回传 PAUSED_PLAYBACK，SEQ 严格递增，NT/NTS/SID 头正确
- ✅ Set/GetVolume 精确读回；未知 Action X_SetAVTransportURI 返回 401 不崩溃；错误媒体地址 → 错误覆盖层 UI

该轮实测发现并修复了三个"仅真机暴露"的缺陷（JVM 单测全绿但 Android 上必现）：

1. `DocumentBuilderFactory.setXIncludeAware(false)` 在 Android 抛 `UnsupportedOperationException` → 所有 SOAP 请求 400（改为 try/catch 包裹）
2. `HttpURLConnection.setRequestMethod("NOTIFY")` 被 Android 拒绝 → GENA 事件永远发不出去（NOTIFY 改为原生 Socket 手写报文）
3. 待机页 Fragment 先于 ServiceConnection 创建导致永远显示"网络未连接"（Activity 在连接就绪后主动刷新当前页）

模拟器限制说明：模拟器 NAT 网卡不支持 SSDP 组播收发（日志可见 SSDP send failed），该层在模拟器阶段仅由单元测试覆盖报文构造/解析，现已由真机实测闭环（见上）。

## 架构

```
com.tvcast/
├─ core/       CastService（前台服务+MulticastLock+通知+UI 唤起）、NetworkWatchdog（断网/换网重宣告）、
│              Settings（UDN 持久化/设备名/自启）、BootReceiver
├─ dlna/       自研 UPnP 栈（纯 Kotlin/JVM，不依赖 Android API，可被 JVM 测试直接驱动）
│   ├─ ssdp/   NOTIFY alive(30s)/bybye 六元组宣告、M-SEARCH MX 随机延迟单播响应
│   ├─ http/   自研 mini HTTP server（80→49152+ 回落）、描述/SCPD/控制/事件端点路由
│   ├─ soap/   SoapDispatcher（标准 fault）、AVTransport（隐式播放+查询全集）、
│   │          RenderingControl（系统音量）、ConnectionManager（宽泛静态 sink 列表）
│   ├─ gena/   EventBus（订阅/SEQ:0 初始快照/续订/超时清理）、LastChangeXml（AVT/RCS 嵌套事件）
│   ├─ didl/   DIDL-Lite 宽容解析（多重转义/无命名空间/恶意输入均 fail-soft）
│   └─ DmrStack 总编排：IP 变化 → bybye → 重建描述（UDN 不变）→ 重宣告
├─ playback/   PlayerController（ExoPlayer 状态机/音频焦点/进度跨线程缓存）、
│              SystemVolumeControl、ImageDisplayer（按屏幕分辨率降采样）
└─ ui/         单 Activity 三 Fragment：Standby / Playing / Settings
```

关键设计点（详见 spec §4）：隐式播放（SetAVTransportURI 即起播）、UDN 永久持久化、LastChange SEQ:0 全量快照、未知 Action 返回 401 不崩溃、时间格式 H:MM:SS。

## 测试覆盖（75 项）

| 套件 | 数量 | 覆盖 |
| --- | --- | --- |
| FakeControlPointTest | 13 | 裸 socket 端到端：dd.xml/SCPD → 隐式播放 → X_ 未知动作 401 → 快速切台 → Play/Pause/Stop/Seek/查询 → SUBSCRIBE 快照/续订/退订 → 音量 → GetProtocolInfo |
| GenaEventBusTest | 13 | 订阅/续订/退订/超时清理/SEQ 递增/412/400/超时解析 |
| AvTransportServiceTest | 12 | 全动作 + 402/401/710 fault + 查询字段形态 |
| SsdpMessagesTest | 9 | 六元组 alive/bybye/USN 组合/M-SEARCH 响应/报文解析 |
| DidlParserTest | 11 | 规范/多重转义/无命名空间/恶意 DOCTYPE/畸形输入 fail-soft |
| UpnpTimeTest / MediaKindResolverTest / ProtocolInfoListTest / LastChangeXmlTest | 16 | 时间格式、类型推断、sink 列表、事件 XML 转义 |

## 真机手测清单（对应里程碑验收）

已实测通过：雷鸟 65 英寸电视 + B站客户端 + 手机系统相册（2026-10-22）。

1. **M1 发现**：安装 APK → 待机页显示设备名与 `http://IP:端口` → BubbleUPnP 搜到 "TVCast-XXXX"，能拉取设备描述
2. **M2 播放**：BubbleUPnP 推公网 mp4 URL → 电视自动进入全屏播放（不发 Play 也起播）；暂停/停止/进度查询生效
3. **M3 闭环**：手机端进度条实时走动；拖进度、调音量、推图片（相册）、推音乐均可用；另一台手机可接管（last-writer-wins）
4. **M4 兼容**：B站/爱奇艺/腾讯视频投屏；WiFi 断开重连后 5s 内重新可被发现；重启盒子后开机自启生效
5. **报文级对拍**：Windows 用 Intel UPnP Developer Tools（DeviceSpy）、Linux 用 `gupnp-av-cp` 逐 Action 比对

## 已知限制与注意事项

- **Android 10+ 后台启动 Activity 受限**：服务中收到投屏会尝试唤起播放页，个别 ROM 会拦截（此时手动打开 App 即可，播放不中断）
- **厂商 ROM 后台管控**：FGS 合规只是及格线，待机页已提示用户把应用加入白名单
- **TV banner 为矢量占位**：上架前建议替换为 320×180 PNG（`res/drawable/tv_banner.xml`）
- **老盒子冷门格式**：ProtocolInfo 宽泛声明，硬解不支持时显示错误提示，V2 计划接入 Media3 ffmpeg 扩展
- 部分盒子限制组播：已持 MulticastLock；若仍搜不到，检查路由器 AP 隔离/组播设置
