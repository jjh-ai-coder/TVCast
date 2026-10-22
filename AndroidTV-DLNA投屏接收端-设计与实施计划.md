# AndroidTV-DLNA投屏接收端-设计与实施计划

# Android TV DLNA 投屏接收端（TVCast）设计与实施计划

> 日期：2026-10-22（v1.1 修订版）
> 状态：待确认
> 需求结论：DLNA 推媒体（接收端）｜只做 TV 端 APK｜发送端依赖现成 Android 手机 App｜新老设备都要兼容（minSdk 21）

---

## 修订记录（v1.1，吸收外部评审）

| 级别 | 条目 | 处理 |
| --- | --- | --- |
| P0-① | NanoHTTPD 无法接收 GENA SUBSCRIBE（非标准 HTTP 动词） | **已核验源码属实**：2.3.1（Maven 最新发布版，2016-08）`Method` 枚举无 SUBSCRIBE/UNSUBSCRIBE/NOTIFY，未知动词直接回 400。HTTP 服务改为**自研 mini HTTP server**，彻底移除 NanoHTTPD 依赖（同时消除"否决 Cling 却选 2016 年库"的双标问题） |
| P0-② | 遗漏 `WifiManager.MulticastLock` | 已加入 CastService 职责与权限清单。不持锁则 WiFi 上收不到 M-SEARCH 组播，手机永远搜不到电视 |
| P1-③ | UDN 持久化 + IP 变化时描述 XML 重建 | 已写入 §5 协议实现细则 |
| P1-④ | LastChange 嵌套 XML 结构 + SUBSCRIBE 初始全量快照 | 已写入 §5，附 XML 模板 |
| P1-⑤ | 查询类 Action 覆盖不足 | 已补：GetTransportInfo / GetMediaInfo / GetCurrentTransportActions / Next / Previous（空实现） |
| P1-⑥ | DIDL-Lite（CurrentURIMetaData）宽容解析 | 已写入 §5 及模块结构 |
| P1-⑦ | 端口策略 | 已明确：尝试 80 → 失败回落高位端口 + 记日志。**含技术修正**：Android 普通应用无法绑定 \<1024 特权端口，原生 ROM 上 80 必然失败，此策略仅在个别宽松 TV ROM 生效，预期以高位端口为主（规范 CP 按 SSDP LOCATION 头取地址，任意端口可用） |
| P1-⑧ | §5 与风险表 ProtocolInfo 矛盾 | 定论为**宽泛静态列表**（运行时硬解探测不可靠）；播放失败走错误 UI + V2 ffmpeg 扩展兜底 |
| P2 | 流程图时序、代码量估算、报文级测试工具、fake 控制点回归测试、KEEP\_SCREEN\_ON/音频焦点/多控制点并发/图片降采样、厂商 ROM 白名单引导、里程碑工期 | 全部吸收（见 §4/§6/§7/风险清单） |

---

## 1. 产品定义与范围

**做什么**：Android TV 上的 DLNA/UPnP MediaRenderer（DMR）接收端 APK。手机上支持 DLNA 投屏的现成 App（B站、爱奇艺、腾讯视频、系统相册、BubbleUPnP 等）在局域网内发现电视后，将视频/音乐/图片推送到电视大屏播放。

**明确不做（本期）**：

- 屏幕镜像（自研私有协议，后续版本）
- AirPlay / Google Cast / Miracast（认证或固件门槛，第三方 APK 不可行）
- 手机端发送 App（第二阶段）

**不可行性说明**（为什么选 DLNA）：

| 协议 | 结论 |
| --- | --- |
| Google Cast | 需 Google 官方认证（Chromecast built-in），第三方无法实现 |
| Miracast | 需固件级 WiFi Display Sink 支持，普通 APK 无法实现 |
| AirPlay | 基于逆向协议，兼容性与法律风险高 |
| **DLNA/UPnP** | **纯应用层可实现，国内主流视频 App 均内置 DLNA 投屏，✅ 本期方案** |

**TV 端界面**（共 3 屏）：

1. **待机页**：设备名、等待投屏提示、网络连接状态、（保活引导入口）
2. **播放页**：全屏视频/图片（显示手机推来的媒体标题），遥控器可调进度、音量
3. **设置页**：修改设备名、开机自启开关

---

## 2. 技术选型

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 语言 | Kotlin | Android 官方首选 |
| 系统版本 | minSdk 21，targetSdk 34（TV） | Android 5.0 起步覆盖老盒子；**Media3 最低要求恰好是 API 21，严格匹配** |
| UI 框架 | 传统 View + Fragment + ConstraintLayout | 仅 3 屏不需要 Leanback 浏览框架；Compose for TV 在低端盒子渲染开销大 |
| 播放引擎 | Media3 ExoPlayer 1.x | HTTP/HLS/渐进式流、硬解优先、API 21 兼容 |
| 图片显示 | Glide | 图片投屏显示（强制降采样，见 §5） |
| UPnP 栈 | **自研精简实现**（SSDP + SOAP + GENA 子集） | DMR 协议子集固定且小；Cling 2017 年停维护、jUPnP 体积大且 Android TV 服务集成复杂；商业投屏 App 普遍自研，完全可控。**代码量按 3.5k\~****4.5k 行估算**（含 LastChange 事件 XML、DIDL-Lite 宽容解析、GENA 续订/超时清理、SOAP fault 生成） |
| HTTP 服务 | **自研 mini HTTP server（约 300 行）** | GENA 使用非标准动词 SUBSCRIBE/UNSUBSCRIBE；NanoHTTPD 2.3.1 对未知动词直接回 400（已核验源码，见修订记录 P0-①）。自研只需解析 request line + headers + body——GENA 的 SID/TIMEOUT/NT/NTS 头本来就是自己最清楚的部分 |
| HTTP 回调 | HttpURLConnection | 向订阅者发 GENA NOTIFY，不引 OkHttp |
| 持久化 | SharedPreferences | UDN、设备名、设置项 |

**权限清单**：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`（MulticastLock 必需）、`RECEIVE_BOOT_COMPLETED`（开机自启）、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`（API 34+ FGS 类型声明）

---

## 3. 模块结构

```
app/src/main/java/com/tvcast/
├─ core/
│   CastService.kt          // 前台服务：常驻、开机自启、生命周期编排
│                           // ★ 持有 WifiManager.MulticastLock：
│                           //   acquire() + setReferenceCounted(false)，服务销毁时 release()
│   NetworkWatchdog.kt      // 网络监听：断网/换网/IP 变化 → ssdp:bybye → 重建描述 XML → 重宣告
│   Settings.kt             // UDN（首次生成后持久化，重启不变）、设备名等
├─ dlna/
│   ├─ ssdp/SsdpServer.kt   // UDP 组播 239.255.255.250:1900：NOTIFY alive/bybye、M-SEARCH 响应
│   ├─ http/MiniHttpServer.kt      // 自研：解析 request line + headers + body，
│   │                              // 支持 GET(描述XML) / POST(SOAP) / SUBSCRIBE / UNSUBSCRIBE
│   ├─ http/DescriptionServer.kt   // 设备描述 DDD + 3 个 SCPD XML + GENA 订阅端点路由
│   ├─ soap/SoapDispatcher.kt      // SOAP Action 解析/分发/响应 XML、标准 SOAP fault 生成
│   ├─ soap/AvTransportService.kt  // SetAVTransportURI/Play/Pause/Stop/Seek/GetPositionInfo
│   │                              // ★ GetTransportInfo/GetMediaInfo/GetCurrentTransportActions
│   │                              // ★ Next/Previous（空实现，返回成功即可）
│   ├─ soap/RenderingControl.kt    // SetVolume/GetVolume/SetMute/GetMute → AudioManager
│   ├─ soap/ConnectionManager.kt   // GetProtocolInfo（宽泛静态 MIME 列表，见 §5）
│   ├─ didl/DidlParser.kt          // ★ DIDL-Lite 宽容解析（dc:title/upnp:class），fail-soft
│   ├─ gena/EventBus.kt            // 订阅管理 + LastChange NOTIFY 回调
│   │                              // ★ 初始订阅先回 SEQ:0 全量快照；续订（仅带 SID）；超时清理
│   └─ DmrDevice.kt               // 设备模型：UDN、friendlyName、服务注册、描述 XML 生成
├─ playback/
│   PlayerController.kt     // ExoPlayer 封装：NO_MEDIA/LOADING/PLAYING/PAUSED/STOPPED 状态机
│                           // 状态变化 → AVTransport LastChange 事件
│                           // ★ FLAG_KEEP_SCREEN_ON（播放期常亮）
│                           // ★ 音频焦点管理（AudioManager，失去焦点暂停）
│   ImageDisplayer.kt       // 图片 URI → Glide 显示，★ 强制降采样（防老盒子 4K 图 OOM）
├─ ui/
│   MainActivity.kt         // 单 Activity 三 Fragment：Standby/Playing/Settings
└─ res/                     // 布局、TV banner、leanback launcher intent-filter
```

**各模块职责一句话**：

- `ssdp/`：让手机在局域网里"看见"电视（收组播依赖 MulticastLock）
- `http/` + `soap/`：让手机"看懂"电视有什么能力、下发播放控制命令
- `gena/`：把电视的播放状态（起播/暂停/进度）实时推回手机
- `playback/`：真正干活的播放器，状态机保证命令与事件一致
- `core/`：常驻保活、MulticastLock、断网重连、设置与 UDN 持久化

---

## 4. 核心协议流程

```
手机 App (CP)                      TV 端 (TVCast)
   │                                  │
   │◄──── NOTIFY ssdp:alive (组播, 30s 周期) ────│  ① 宣告
   │───── M-SEARCH (ST: MediaRenderer) ───►     │  ② 发现（TV 端收组播需 MulticastLock）
   │◄──── 200 OK (LOCATION=描述文件地址) ───────│
   │───── GET /dd.xml ────────────────►         │
   │     （解析出 AVTransport controlURL 等）    │
   │───── SOAP: SetAVTransportURI ────►         │  ③ 控制（隐式播放：收到 URI 即自动起播）
   │───── SOAP: Play/Pause/Stop/Seek ──►        │
   │───── SUBSCRIBE (CALLBACK, TIMEOUT) ──►     │  ④ 事件（先订阅）
   │◄──── 200 OK (SID, TIMEOUT) ────────────────│
   │◄──── NOTIFY SEQ:0 初始全量快照 ─────────────│     订阅成功即回全量状态
   │◄──── NOTIFY SEQ:n (LastChange 状态/进度) ──│     此后状态变化增量推送
   │                                  │
   │      断网/换网/IP 变化：bybye → 重建描述 XML（新 IP/端口）→ 重宣告（UDN 不变）
```

1. **宣告**：App 启动/联网 → CastService 前台服务启动 → acquire MulticastLock → SsdpServer 组播 `NOTIFY ssdp:alive`（每 30s 续宣告），同时监听 M-SEARCH 并按 `ssdp:all` / `MediaRenderer` / `AVTransport` 等 ST 响应
2. **发现**：手机 App 拉取描述 XML → 解析出 AVTransport/RenderingControl 控制地址
3. **控制**：SOAP POST → `SetAVTransportURI`（记录 URI，按 MIME 分流视频/音频/图片；**隐式播放**：多数国内 App 只调此动作不调 Play → 收到即准备并自动起播）→ `Play` → ExoPlayer 起播；`Pause/Stop/Seek/SetVolume` 同步映射到播放器/系统音量
4. **回传**：CP 先 `SUBSCRIBE`（带 CALLBACK 回调 URL 和 TIMEOUT）→ TV 回 200（SID、TIMEOUT）并**立即推送 SEQ:0 初始全量快照** → 此后状态变化发 `NOTIFY`（LastChange，SEQ 递增）；CP 定期续订（仅带 SID）；`GetPositionInfo` 轮询兜底
5. **网络变化**：断网/换网/IP 变化 → `NOTIFY ssdp:bybye` → 以新 IP/端口重建描述 XML（UDN 不变）→ 重宣告；服务销毁 → bybye + 停 HTTP 服务

---

## 5. 协议实现细则（spec 必填章节）

### 5.1 UDN 持久化与描述文件重建

- UDN（`uuid:xxx`）首次启动生成后写入 SharedPreferences，**永久不变**——变了会导致手机端缓存成僵尸设备
- DDD/SCPD 中 controlURL/eventURL 是**绝对 URL**：IP 变化（DHCP 续租、换网）后旧 URL 全部失效 → NetworkWatchdog 检测到 IP 变化即走 §4 流程 5 重建重宣告

### 5.2 LastChange 事件结构

LastChange 不是简单字段，是**嵌套的 AVT/RCS 事件 XML**（带 AVT 命名空间，整体作为转义字符串包在 GENA 属性里）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
  <e:property>
    <LastChange>&lt;Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"&gt;&lt;InstanceID val="0"&gt;&lt;TransportState val="PLAYING"/&gt;&lt;AVTransportURI val="http://..."/&gt;&lt;/InstanceID&gt;&lt;/Event&gt;</LastChange>
  </e:property>
</e:propertyset>
```

- RenderingControl 侧对应 `<Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/">`（Volume/Mute）
- **初始订阅必须先回一发全量快照**（SEQ:0），做错则手机端进度条不动/乱跳——正是 M3 的验收点
- GENA NOTIFY 头：`NT: upnp:event`、`NTS: upnp:propchange`、`SID: uuid:...`、`SEQ: n`
- 订阅默认 TIMEOUT 1800s；续订请求仅带 SID（无 NT）；超时未续订的订阅清理掉

### 5.3 Action 覆盖清单

| 类别 | Action |
| --- | --- |
| 控制 | SetAVTransportURI、Play、Pause、Stop、Seek、Next/Previous（空实现返回成功）、SetVolume、SetMute |
| 查询 | GetPositionInfo、GetTransportInfo、GetMediaInfo、GetCurrentTransportActions、GetVolume、GetMute、GetProtocolInfo |

- 时间格式严格 `H:MM:SS`（超 1 小时为 `H:MM:SS` 或 `HH:MM:SS` 均接受，输出统一 `H:MM:SS`）；`TrackDuration`/`RelTime`/`TrackMetaData` 字段拼写按规范——**这是兼容重灾区**
- 未知 Action（如 B 站 `X_SetAVTransportURI`）：记日志、返回标准 UPnP 错误码（401 Invalid Action），不崩溃

### 5.4 DIDL-Lite 元数据（宽容解析）

`SetAVTransportURI` 第二参数 CurrentURIMetaData 携带 XML 转义的 DIDL-Lite；解析出 `dc:title`（标题）与 `upnp:class`（辅助判别媒体类型）。**手机端 URI 的 XML 转义错误是常态**——解析必须 fail-soft：任何解析失败不报错、不影响播放，仅回退显示 URI 文件名。

### 5.5 端口策略

MiniHttpServer 启动：尝试绑定 80 → 失败（`EACCES`）则回落高位随机端口（49152+）并记日志。
**技术事实修正**：Android 普通应用无法绑定 \<1024 特权端口，原生 ROM 上 80 必然失败，此策略仅在个别宽松 TV ROM 生效，**实际运行预期以高位端口为主**——规范 CP 从 SSDP 报文 LOCATION 头取完整地址，任意端口可用；绑定 80 只是针对不规范的国内发送端的兜底尝试。

### 5.6 GetProtocolInfo 定论：宽泛静态列表

返回宽泛 sink 列表（video/mp4、mkv、flv、avi、ts、m2ts、audio/mpeg、flac、image/jpeg、png…，带 DLNA.ORG\_PN 参数）。**不做运行时硬解能力收敛**（探测不可靠）；播放失败走明确错误 UI（"设备不支持该格式"），V2 接入 Media3 ffmpeg 扩展兜底。

### 5.7 播放期行为与多控制点并发

- 播放期窗口 `FLAG_KEEP_SCREEN_ON` 常亮；`AudioManager` 音频焦点（失去焦点自动暂停）
- 多控制点并发策略：**last-writer-wins**（最后一个下发命令的控制点获得控制权），记日志可追溯

---

## 6. 里程碑、工期量级与验收

| 里程碑 | 内容 | 工期量级 | 验收标准 |
| --- | --- | --- | --- |
| **M1 协议骨架** | SSDP 宣告/响应（含 MulticastLock）+ 描述 XML 服务 | 2\~3 天 | BubbleUPnP 能发现设备并显示名称（"TVCast-XXXX"） |
| **M2 视频可播** | AVTransport 核心动作（含查询类）+ ExoPlayer + 隐式播放 | 3\~5 天 | BubbleUPnP 推公网 mp4 URL → 电视全屏播放；暂停/停止/进度查询生效 |
| **M3 控制闭环** | GENA（初始快照/续订/超时）+ 音量 + Seek + 图片/音乐 + DIDL-Lite 标题 | 5\~8 天 | 手机端进度条实时走动；音量、拖进度、相册推图、推音乐全部可用；**fake 控制点回归测试全绿** |
| **M4 真实兼容 + 打磨** | 真实 App 兼容调试、UI、保活、白名单引导 | 5\~10 天 | B站/爱奇艺/腾讯视频/相册实测清单通过；三屏 UI 完成；开机自启；断网重连（WiFi 重连后 5s 内重新可被发现） |

---

## 7. 测试计划

- **单元测试**（JUnit）：SSDP 报文构造/解析、SOAP Action 解析与响应 XML、SOAP fault、播放状态机转换、GetProtocolInfo 列表、DIDL-Lite 宽容解析（含恶意/畸形输入）
- **fake 控制点回归测试**（自写，可自动化，不依赖真机）：
  - **隐式播放**：仅 SetAVTransportURI 不发 Play → 必须自动起播
  - **未知 Action**：发 `X_SetAVTransportURI` → 返回 401 且不崩溃
  - 快速连续 SetAVTransportURI（切台）→ 状态机不错乱
  - SUBSCRIBE 初始快照（SEQ:0 全量）、续订（仅 SID）、超时清理
- **报文级对拍**：Windows 用 Intel UPnP Developer Tools（DeviceSpy）、Linux 用 `gupnp-av-cp`，逐 Action 对报文
- **集成手测清单**：BubbleUPnP（协议正确性基准）→ B站/爱奇艺/腾讯视频/相册（真实场景）→ 老盒子真机（Android 5\~9，低端性能与兼容）→ 断网重连、锁屏中断投屏场景

---

## 8. 批准后的执行步骤

1. 将本设计整理为 spec 文件：`docs/superpowers/specs/2026-10-22-tv-dlna-receiver-design.md`
2. 用 writing-plans 产出逐任务实施计划
3. 在工作区创建完整 Android 工程源码（gradle 配置 + 上述模块），按 M1→M4 里程碑顺序实现
4. 如本机具备 Android SDK/Gradle 环境则运行构建与单元测试验证；否则静态交付并注明验证方式

---

## 附：风险清单

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 国内各 App DLNA 实现有私有扩展 | 个别 App 投不上 | M4 真机逐 App 调试；未知 action 容错返回 401 并记日志；隐式播放/X\_SetAVTransportURI 已入 fake 控制点回归测试 |
| 老盒子硬解能力弱 | 高码率/冷门格式卡顿 | ProtocolInfo 宽泛声明（§5.6 定论）；播放失败明确报错 UI；V2 ffmpeg 扩展兜底 |
| 部分盒子限制组播 | 手机搜不到设备 | MulticastLock 已列 P0 职责；待机页提供 IP 直连提示；M-SEARCH 单播兜底 |
| Android 各版本前台服务政策 | 保活失效 | 按 targetSdk 规范声明 FGS 类型 mediaPlayback；提供开机自启开关 |
| **厂商 ROM 杀后台白名单（国产 TV 现实）** | FGS 合规只是及格线，各厂商 ROM 有自己的后台管控 | 待机页引导用户进系统设置加应用白名单（M4 交付） |
| 4K 大图 OOM（老盒子） | 图片投屏崩溃 | Glide 强制按屏幕分辨率降采样 |
