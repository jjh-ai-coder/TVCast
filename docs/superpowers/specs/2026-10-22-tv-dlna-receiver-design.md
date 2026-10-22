# TVCast — Android TV DLNA 投屏接收端 设计 Spec

> 日期：2026-10-22 ｜ 状态：已确认（v1.1 评审意见全部吸收）｜ 来源：`AndroidTV-DLNA投屏接收端-设计与实施计划.md`

## 1. 范围

Android TV 上的 DLNA/UPnP MediaRenderer（DMR）接收端 APK。手机侧使用现成 DLNA 发送端 App（BubbleUPnP、B站、爱奇艺、腾讯视频、系统相册等）发现电视并推送视频/音乐/图片。

不做：屏幕镜像、AirPlay/Google Cast/Miracast、手机端发送 App。

三屏 UI：待机页（设备名/等待投屏/网络状态/保活引导）、播放页（全屏媒体+标题，遥控调进度音量）、设置页（设备名/开机自启）。

## 2. 技术决策

| 决策点 | 定论 |
| --- | --- |
| 语言/版本 | Kotlin，minSdk 21，targetSdk 34 |
| UI | 传统 View + Fragment + ConstraintLayout |
| 播放 | Media3 ExoPlayer 1.x（硬解优先） |
| 图片 | Glide，强制按屏幕分辨率降采样 |
| UPnP 栈 | 自研精简实现（SSDP+SOAP+GENA 子集，约 3.5k~4.5k 行） |
| HTTP 服务 | 自研 mini HTTP server（NanoHTTPD 2.3.1 无法接收 GENA SUBSCRIBE 非标准动词，已核验源码，弃用） |
| HTTP 回调 | HttpURLConnection（GENA NOTIFY） |
| 持久化 | SharedPreferences（UDN/设备名/设置） |

权限：INTERNET、ACCESS_NETWORK_STATE、ACCESS_WIFI_STATE、CHANGE_WIFI_MULTICAST_STATE、RECEIVE_BOOT_COMPLETED、FOREGROUND_SERVICE、FOREGROUND_SERVICE_MEDIA_PLAYBACK、WAKE_LOCK。

## 3. 架构

```
com.tvcast/
├─ core/     CastService（前台服务+MulticastLock+编排）、NetworkWatchdog、Settings、BootReceiver
├─ dlna/     SsdpServer、MiniHttpServer、DescriptionServer(DMM 描述/SCPD/路由)、SoapDispatcher、
│            AvTransport/RenderingControl/ConnectionManager 三服务、DidlParser(宽容)、GenaEventBus、
│            DmrStack(编排)、DmrDevice(描述 XML)、LastChangeXml、ProtocolInfoList、MediaKindResolver、NetInfo
├─ playback/ PlayerController(ExoPlayer 封装+状态机+音频焦点)、SystemVolumeControl、ImageDisplayer
└─ ui/       MainActivity(单 Activity 三 Fragment)：Standby / Playing / Settings
```

dlna 包为纯 Kotlin/JVM（不依赖 Android API），RendererPlayer/VolumeControl 为接口，由 playback 层实现——协议栈可被 JVM 单元测试与 fake 控制点回归测试直接覆盖。

## 4. 协议要点（必须遵守的实现细则）

1. **MulticastLock**：CastService 持有 `WifiManager.MulticastLock`（setReferenceCounted(false)），否则收不到 M-SEARCH 组播。
2. **UDN 持久化**：首次生成写入 SharedPreferences 永不变更；IP 变化时仅重建描述 XML（绝对 URL）并重宣告。
3. **SSDP**：NOTIFY alive 每 30s（NT/USN 六元组：rootdevice/uuid/device/3 service）；响应 M-SEARCH（ssdp:all/rootdevice/uuid/精确类型），MX 随机延迟单播 200 OK；断网/IP 变化先 bybye。
4. **隐式播放**：收到 SetAVTransportURI 即准备并自动起播（多数国内 App 不发 Play）。
5. **LastChange**：嵌套 AVT/RCS Event XML 作为转义字符串包进 GENA propertyset；SUBSCRIBE 成功先回 SEQ:0 全量快照，此后增量 NOTIFY，SEQ 每订阅递增；续订仅带 SID；TIMEOUT 默认/cap 1800s，超时清理。
6. **Action 覆盖**：控制类 SetAVTransportURI/Play/Pause/Stop/Seek/Next/Previous(空实现)/SetVolume/SetMute；查询类 GetPositionInfo/GetTransportInfo/GetMediaInfo/GetCurrentTransportActions/GetVolume/GetMute/GetProtocolInfo。时间格式 `H:MM:SS`（输入兼容 HH:MM:SS）。未知 Action（X_SetAVTransportURI 等）返回 UPnP 401 fault，不崩溃。
7. **DIDL-Lite 宽容解析**：dc:title/upnp:class，双重转义容错，任何失败 fail-soft 回退 URI 文件名。
8. **端口**：尝试 80（原生 ROM 必然 EACCES）→ 回落 49152+ 高位随机端口；CP 从 SSDP LOCATION 取地址，任意端口可用。
9. **ProtocolInfo**：宽泛静态 sink 列表（mp4/mkv/avi/flv/ts/m2ts/mov/3gp/webm/wmv/mp3/flac/aac/wav/ogg/jpeg/png…带 DLNA.ORG_PN/OP 参数）；播放失败走错误 UI，V2 以 ffmpeg 扩展兜底。
10. **播放期行为**：FLAG_KEEP_SCREEN_ON 常亮；音频焦点失去自动暂停（can-duck 降音量）；多控制点 last-writer-wins。

## 5. 状态机

RendererPlayer（PlayerController 实现）：
`NO_MEDIA_PRESENT / TRANSITIONING / PLAYING / PAUSED_PLAYBACK / STOPPED`
- setMedia：置 TRANSITIONING→prepare→自动起播；IMAGE 类型不经播放器直接 PLAYING（UI 层 Glide 展示）
- 错误：置 STOPPED + lastError（TransportStatus=ERROR_OCCURRED），UI 显示"设备不支持该格式"

## 6. 里程碑与验收

| 里程碑 | 验收 |
| --- | --- |
| M1 协议骨架 | BubbleUPnP 能发现 "TVCast-XXXX" 并拉到描述/SCPD |
| M2 视频可播 | BubbleUPnP 推 mp4 全屏播放；暂停/停止/进度查询生效；隐式播放 |
| M3 控制闭环 | 手机端进度条实时走动；音量/Seek/图片/音乐可用；fake 控制点回归全绿 |
| M4 真实兼容 | B站/爱奇艺/腾讯视频/相册实测；三屏 UI；开机自启；WiFi 重连 5s 内重新可发现 |

## 7. 测试

- JVM 单元测试：SSDP 报文、SOAP 解析/fault、状态机、DIDL 宽容解析、时间格式、ProtocolInfo、GENA 订阅/续订/超时
- fake 控制点回归（JVM、裸 socket 对 MiniHTTPServer）：隐式播放、未知 Action 401、快速切台、SUBSCRIBE 初始快照/续订/退订
- 报文级对拍：Intel DeviceSpy / gupnp-av-cp
- 真机手测清单：BubbleUPnP → 国内 App → 老盒子（Android 5~9）→ 断网重连

## 8. 风险与对策（摘要）

| 风险 | 对策 |
| --- | --- |
| 国内 App 私有扩展 | 未知 action 401+日志；隐式播放/X_* 已入回归测试 |
| 老盒子硬解弱 | 宽泛 ProtocolInfo；失败明确报错；V2 ffmpeg |
| 盒子限制组播 | MulticastLock；待机页展示 IP；M-SEARCH 单播可达 |
| FGS 政策 | mediaPlayback 类型声明+开机自启；厂商白名单引导（M4） |
| 4K 图 OOM | Glide override 屏幕分辨率降采样 |
