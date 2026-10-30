# AWA 与黑壳模块静态逆向研究、功能映射与 TNT 重构记录

## 1. 范围与证据边界

研究对象为用户提供的两个 APK：

- AWA：`awa(1).apk`
- 黑壳：`嘿壳模块_Hicores.20260927.155420.apk`

本次依据既有静态审计输出、DEX 类定义差异 CSV、URL 字符串清单和 PacketHelperDialog 调用引用线索整理。未在真机运行两个 APK，未进行实时网络抓包、Frida 动态追踪、完整方法级反编译审查或服务器端协议验证。

因此，本文将结论分为：
- **静态确认**：Manifest、DEX 类定义、方法名/调用引用、资源和字符串中可直接观察到的信息。
- **功能推断**：根据命名、界面文案与调用引用归纳出的功能用途。
- **未验证**：必须运行 APK、跟踪调用参数或观察网络请求才能确认的行为。

静态分析不能证明 APK 安全，也不能证明每个类在当前 QQ 版本上可正常运行。

## 2. 两个 APK 的结构对比

| 指标 | AWA | 黑壳 |
|---|---:|---:|
| DEX 文件数 | 2 | 3 |
| 唯一类定义总数 | 12,441 | 12,835 |
| 两包共有类 | 12,439 | 12,439 |
| 仅该包存在的类 | 2 | 396 |
| APK 包名 | `moe.awa` | `moe.awa` |
| Xposed 作用域 | `com.tencent.mobileqq` | `com.tencent.mobileqq` |

两个 APK 的 `libdexkit.so`、`libmmkv.so` 和 `libsilk_encoder.so` 三个原生库哈希相同。两包的 version-control 元数据都出现 revision `3b8b2eaa17379888b8c2afee04434e7745d98226`，但 DEX、Manifest 和资源并不完全相同，因此该元数据不能证明两包源代码一致。

两个 APK 都只在 Manifest 表层声明 INTERNET 与模块自定义动态接收器权限；表层未发现 SMS、通讯录或无障碍服务权限组合。黑壳的体积更大，部分增量来自登录背景视频和字体资源。包名相同但签名证书不同，不能视为可直接覆盖安装的同一签名应用。

## 3. AWA 的主要功能方向

AWA 与黑壳共享绝大多数类，说明两者有明显共同代码基础。静态资料可支持的功能方向包括：

1. QQ 消息与聊天功能扩展。
2. 消息撤回相关处理、消息组件和 Hook 扩展。
3. 开发者工具与宿主接口辅助。
4. 本地配置、模块设置与功能开关。
5. 以 Xposed/LSPosed 模块方式注入 QQ 进程。

本轮现有差异表中，AWA 独有的两个类是 `CatchMqqapi$$ExternalSyntheticLambda0` 和 `ZeroRedPacket$entry$1`。仅凭这两个类名不能确认完整业务行为，也不能据此推断其网络或发送逻辑。

## 4. 黑壳相对 AWA 的增量功能

下列条目来自黑壳独有 DEX 类名和相关方法/字符串线索。类名可以反映代码模块意图，但不能单独证明功能在实际环境中一定可用。

### 4.1 聊天区、消息菜单与快捷操作

- `LocationMessageMenu`、`LocationShareArkMenu`：位置消息或位置分享相关菜单。
- `MessageEmojiLikesMenu`：消息表情互动/点赞菜单。
- `LongVoiceMenu`：长语音相关菜单。
- `CopyPictureLink`、`PictureMessageTools`、`MagicPicPrefill`：图片消息辅助与链接处理。
- `MessageMeta`、`MessageMetaConfig`、`MessageMetaDisplay`、`MessageMetaFormatter`：消息元信息及其显示配置。
- `ShortcutBarPresetHelper`：快捷栏预设辅助。

### 4.2 卡片与媒体工作流

- `LocalCardPresetManager`：本地卡片预设管理。
- `PicView2ArkMenu`、`UploadedPicArkFlow`、`SendUploadedPicArk`：图片与 Ark 卡片相关工作流。
- `DirectVideoCardSender`、`DouyinVideoParser`：视频卡片与短视频链接处理方向。
- `QqMusicSongPicker`：音乐选择器方向。
- `WorldPlayCardMenu`、`GroupJumpArkMenu`、`MiniAppArkPresetDialog`：游戏/群聊/小程序卡片相关入口。

### 4.3 查询、群聊和主题扩展

- `AppTreasureSearch`、`MagicAppInfoLookup`：应用信息或内容查询方向。
- `AutoLocationShare`、`AutoRejectRTC`：位置分享和 RTC 通话处理方向。
- `GiftEffectMenu`：礼物效果菜单。
- `SubIdCatalogUi`：SubId 目录界面。
- `GenshinBackground`、`GenshinSettingsSkin`、`GenshinTheme`、`GenshinSwitch`：自定义主题和界面视觉。

黑壳还包含额外的约 24 MB 背景视频/字体资源；资源体积增加本身不是恶意证据。

## 5. PacketHelperDialog 与预设工作流

### 5.1 静态可见的编辑模式

DEX 中的 `moe.awa.creator.developer.PacketHelperDialog` 存在以下方法或名称线索：

- `onCreate`、`setContent`、`setContentForLongmsg`
- `showFullScreenJsonEditor`
- `highlightJson`、`updateLineNumbers`
- `compressData`
- `showRepeatSendDialog`

界面字符串可见 `text`、`xml`、`raw`、`ark`、`element` 等模式名，并出现 JSON、Raw(array)、包名、长消息等编辑器相关线索。综合来看，它把内容编辑、类型选择、模板元素复用和消息处理入口聚合在同一个工作台。

### 5.2 高层调用方向

静态调用引用包含以下链路线索：

- `send_ark_msg` → `MsgBuilder.nt_build_ark` → `Nt_kernel_bridge.send_msg`
- `send_text_msg` → `MsgBuilder.nt_build_text` → `Nt_kernel_bridge.send_msg`
- `QPacketHelperKt.buildMessage`、`sendMessage`、`sendPacket`
- `send_ark_msg_by_b77`
- `repeatSendMessages`

可归纳为：用户选择消息类型与编辑内容，程序构造宿主消息对象/协议数据，再进入 QQNT 桥接发送路径。真实参数、签名、错误分支和服务端接收效果仍需运行时验证。

### 5.3 本地预设存储

静态字符串和方法名可见：

- SharedPreferences：`OvO_Presetelems`
- 计数键：`elem_count`
- 名称条目：`OvO_elem_<n>`
- 内容映射：`OvO_content_<...>`
- 方法：`loadPresetElem`、`loadElemContentMap`、`savePresetElem`、`saveElemContentMap`、`deleteElement`
- 文件交换：`importPresetElemsFromFile`、`loadPresetElemsFromFile`、`exportPresetElemsToFile`

跨版本最重要的兼容字段是 `name` 与 `content`；`package_name` 可作为附加元数据。黑壳还出现复合记录分隔符：

```text
*awa_pkg_name_pkg_content_split*
```

TNT 已将该分隔符纳入本地解析兼容，但把导入内容当作不可信文本/JSON，而不是代码或指令。

## 6. 外部服务与潜在风险面

两个 APK 的 URL 字符串清单包含 `api.lzlnb.cn`、`qstory.suzhelan.top`、`fun.hicore.cc`、`funobj.hicore.cc` 等共同域名。黑壳额外出现 `cyapi.top` 的抖音/音乐接口字符串、若干 GitHub 协议版本仓库地址及腾讯官方服务地址。

URL 字符串不等于运行时访问证明；不能据此确认请求频率、传输字段、是否包含账号身份或服务端当前是否可用。静态 DEX 还包含动态类加载、Socket/ServerSocket、NanoHTTPD、Cipher 等类型/字符串引用，需结合真实调用路径、加载来源、访问控制和运行时流量进一步审查。

## 7. TNT 的功能映射原则

| 来源功能方向 | TNT 可复用设计 | 本轮实现/处理 |
|---|---|---|
| 本地卡片/消息预设 | 本地预设库、批量导入导出、搜索 | 保留并强化原有预设工坊 |
| JSON/RAW 编辑体验 | JSON 编辑、格式化、验证、差异比较 | 新增 JSON 差异比较工具；不执行 RAW |
| URL/QQ Scheme | 协议、主机、路径、参数分析 | 增强本地链接分析与 URL 编解码 |
| 快捷栏/快捷文案 | 本地短语管理、收藏 | 新增可保存文案与工具收藏入口 |
| Message Meta | 结构化诊断输出 | 新增设备/QQ/模块环境诊断文本 |
| SubId/工具目录 | 搜索、分类、模块入口 | 新增工具搜索、分类筛选和收藏 |
| 主题/UI | 统一视觉系统和自适应入口 | 重做工具台首页与自适应图标 |

### 本轮新增本地工具

- JSON 差异比较：递归比较对象/数组路径，列出新增、删除、修改字段。
- URL/Base64 编解码：本机处理，不访问远程服务。
- SHA-256：本机计算文本摘要。
- 文本统计：字符、Unicode 代码点、UTF-8 字节、行数和空白分隔词数。
- 工具收藏/搜索/分类：按名称、说明和分类筛选。
- 增强环境诊断：收集 Android/API、ABI、QQ 版本和模块版本；不读取账号 Cookie 或聊天内容。
- 系统分享：通过 Android Sharesheet 交给用户选择的应用。

### 明确保留的安全边界

本轮不移植任意 RAW/协议注入、伪造 UIN/Token/签名、循环群发或自动化跨群重复发送。也不复制 APK 中出现的硬编码 token、账号标识或未经验证的第三方授权接口。若未来增加消息发送功能，应优先使用 QQ 原生可见 UI 或公开授权 API，并设计目标会话确认、发送数量/频率限制、取消入口和审计日志。

## 8. TNT 现有 Hook 功能盘点

当前仓库类路径包含以下既有功能方向（名称来自源码文件，不代表所有 QQ 版本都已实测兼容）：

- 聊天：BilibiliAutoPlayCard、BottomShortcutMenu、ChatScrollMemory、FakeFileRecall、HoldRevokeMsg、MessageEncryptor、MiniAppCardDirectJumpApp、QQBubbleRedirect、SelfMessageReactor、StickerPanelEntry。
- 开发者：GetBknByCookie、GetCookie、InjectVConsole、JumpSchemeUri、QQHookCodec、QQMessageFetcher、QQPacketHelperEntry、QSignHook。
- 娱乐：BlockBadlanguage、CustomSelectFriendUin、DoNotBrushMeOff、FakeGroupOwner、FuckSport、ModifyTextMessage、RespondFace。
- 资料卡：ProfileCardMenu。
- Sigma：QQMessageTracker、QQSurnamePredictor。
- 修复：MarkdownGuard、QQCrashFixer、TextViewLongTextFixHook。

## 9. 图标与作者信息设计

新图标使用深海军蓝底色、几何化 T 形标识、青蓝主色与紫色闪电切面，适配 Android Adaptive Icon 与单色主题图标。应用展示名统一为「TNT · 光流工具箱」。

作者描述统一为：

> TNT（光流）QQNT Android 模块，专注使用体验优化、实用工具与模块化扩展。二次开发作者：亦是光流。基于 ONO 开源项目，遵循 GPL-3.0 并保留上游及贡献者版权声明。

该署名说明 TNT 二次开发者身份，不替代 ONO 原作者、其他贡献者及原项目的版权声明。

## 10. 验证状态与局限

本轮源码修改通过 GitHub Contents API 提交至仓库后，还需要 Android Gradle CI/真实设备验证。当前环境没有 Android SDK/Gradle 依赖缓存，也不能访问用户真实设备，因此不能仅凭静态代码检查宣称 APK 已成功构建或 Hook 功能已在 QQ 真机运行。

后续发布前应检查：
1. GitHub Actions 的 Release 构建结果与 APK Artifact。
2. Android 10+、目标 QQ 版本和 LSPosed/Xposed 加载状态。
3. 新图标在不同 Launcher/Adaptive Icon 蒙版下的显示。
4. 预设导入/导出、JSON 差异、编解码、SHA-256 与系统分享。
5. 原有 Hook 功能逐项回归，并保留可回退 APK/配置备份。
