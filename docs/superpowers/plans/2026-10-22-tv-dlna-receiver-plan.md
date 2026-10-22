# TVCast 逐任务实施计划

> 对应 spec：`docs/superpowers/specs/2026-10-22-tv-dlna-receiver-design.md`
> 构建环境：本机 JDK 17/21 + Gradle 9.3.1（`~/.gradle` 缓存）+ Android SDK `H:\ProgramData\Android\SDK`（platforms 34/35/36）
> 版本组合：AGP 8.13.2 ｜ Kotlin 2.2.21 ｜ Media3 1.4.1 ｜ Glide 4.16.0 ｜ compileSdk 34 / minSdk 21 / targetSdk 34

## 任务清单

### T1 工程骨架
- `settings.gradle.kts`、根 `build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`
- `app/build.gradle.kts`（testOptions.unitTests.isReturnDefaultValues = true）
- `app/src/main/AndroidManifest.xml`（权限、leanback intent-filter、FGS mediaPlayback 类型、banner）
- res：三 Fragment 布局 + themes/colors/strings + 矢量图标/banner
- 验证：`gradlew :app:assembleDebug` 成功出 APK

### T2 dlna 基础类型与工具（纯 JVM）
- `dlna/DlnaTypes.kt`：MediaKind / TransportState / RendererPlayer / VolumeControl 接口
- `dlna/Xml.kt`：escape + 安全 DocumentBuilderFactory（禁 DTD 防 XXE）
- `dlna/UpnpTime.kt`：H:MM:SS 格式化/解析
- `dlna/NetInfo.kt`：本机 IP/组播网卡选择
- 验证：`UpnpTimeTest` 全绿

### T3 描述与 SCPD
- `dlna/DmrDevice.kt`：DDD 生成（绝对 URL、friendlyName 转义）+ AVT/RCS/CM 三份 SCPD 常量
- `dlna/ProtocolInfoList.kt`：宽泛静态 sink 列表
- 验证：`ProtocolInfoListTest`；fake 控制点 GET /dd.xml 与 /scpd/*.xml 返回 200 且内容正确

### T4 MiniHTTP + 路由
- `dlna/http/MiniHttpServer.kt`：request line/headers/body 解析，GET/POST/SUBSCRIBE/UNSUBSCRIBE，端口策略 80→高位
- `dlna/http/DescriptionServer.kt`：路径路由（/dd.xml、/scpd/*、/control/*、/event/*）
- 验证：裸 socket 请求返回正确状态行/头/体

### T5 SOAP 三服务
- `dlna/soap/SoapDispatcher.kt`：SOAPACTION/Body 解析、响应信封、标准 fault（401/402/710…）
- `dlna/soap/AvTransportService.kt`：§4.6 Action 全集 + 隐式播放 + Next/Previous 空实现
- `dlna/soap/RenderingControl.kt`、`dlna/soap/ConnectionManager.kt`
- `dlna/didl/DidlParser.kt`、`dlna/MediaKindResolver.kt`
- 验证：`SoapDispatcherTest`、`AvTransportServiceTest`、`DidlParserTest` 全绿

### T6 GENA
- `dlna/gena/EventBus.kt`：订阅/续订/退订/超时清理，SEQ 递增，初始 SEQ:0 快照，单线程 NOTIFY 保序，可注入 sender/executor/clock
- `dlna/gena/LastChangeXml.kt`：AVT/RCS 嵌套事件 XML + CM 直属属性集
- 验证：`GenaEventBusTest`、`LastChangeXmlTest` 全绿

### T7 SSDP + DmrStack
- `dlna/ssdp/SsdpMessages.kt`：纯报文构造/解析（可测）；`dlna/ssdp/SsdpServer.kt`：组播收发、alive 周期、M-SEARCH MX 延迟响应、bybye
- `dlna/DmrStack.kt`：HTTP+SSDP+GENA 编排、IP 变化重建重宣告（UDN 不变）
- 验证：`SsdpMessagesTest` 全绿

### T8 playback + core
- `playback/PlayerController.kt`：ExoPlayer 状态机、主线程 hop、进度缓存（跨线程读）、音频焦点（can-duck）、错误映射
- `playback/SystemVolumeControl.kt`、`playback/ImageDisplayer.kt`
- `core/Settings.kt`（UDN 持久化/设备名/自启）、`core/NetworkWatchdog.kt`、`core/BootReceiver.kt`
- `core/CastService.kt`：前台服务、MulticastLock、通知、UI 唤起
- 验证：编译通过 + 状态机逻辑由 FakeRenderer 在 T9 覆盖

### T9 UI 三屏
- `ui/MainActivity.kt`（bind service、按 renderer 状态切屏、back 处理）
- `ui/StandbyFragment.kt`、`ui/PlayingFragment.kt`（PlayerView/Glide/错误覆盖层/常亮）、`ui/SettingsFragment.kt`
- 验证：编译通过；手测项列入 README 清单

### T10 fake 控制点回归（JVM 裸 socket）
- `FakeControlPointTest.kt`：dd.xml/scpd → SetAVTransportURI 隐式播放 → 未知 Action 401 → 快速切台 → Play/Pause/Stop/Seek/查询 → SUBSCRIBE 初始快照/续订/退订 → 音量 → GetProtocolInfo
- 验证：`gradlew :app:testDebugUnitTest` 全绿

### T11 构建、测试、交付
- `gradlew :app:assembleDebug :app:testDebugUnitTest`
- `README.md`：构建方法、架构图、真机手测清单、已知限制
