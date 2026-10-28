# TNT：黑壳/AWA 发包工作台与预设机制研究

二次开发作者：亦是光流  
上游代码：[cwuom/ono](https://github.com/cwuom/ono)，GPL-3.0  
分析对象：用户提供的黑壳 APK 与 AWA APK，采用静态分析，没有运行 APK 或抓取真实 QQ 网络流量。

## 1. 证据等级

本研究依据 APK 的 DEX 类名、方法符号、字符串和静态引用线索。静态符号不代表某一调用路径一定在当前 QQ 版本可用；要证明发送成功、接口参数正确或云服务仍在线，需要对应 QQ 版本的运行验证。

## 2. PacketHelper 工作台高层流程

黑壳中可见 moe.awa.creator.developer.PacketHelperDialog，并有文本、XML/卡片、RAW、Ark、自定义元素等编辑入口；方法符号还包含 JSON 编辑、行号更新、压缩内容和重复发送对话框。静态引用指向 MsgBuilder、Nt_kernel_bridge、QPacketHelperKt 等构造/桥接组件。由此可以还原的高层流程是：

1. 用户选择内容模式并编辑文本或 JSON。
2. 从本地自定义元素/预设库读取模板。
3. 按模式做格式转换或组装。
4. 交给模块里的消息构造器/QQNT 宿主桥接层。
5. 再由 QQNT 内部实现处理，并由 UI 显示反馈。

这只是静态调用方向，不是稳定的 QQNT 私有 API 契约。QQ 更新、混淆和服务端校验都可能改变行为。

### TNT 的选择

TNT 实现预设编辑、搜索、格式校验、JSON 美化、HEX/Base64 转换、模板预览、导入导出和 Ark JSON 离线草稿构建。Bot Ark JSON 是开放平台消息结构草稿，不能把它误称为 QQNT 客户端内部协议包。

TNT 不提供任意 RAW/协议包自动发送、不提供重复发送循环、不伪造 UIN/Token/签名，也不读取或上传 Cookie。未来如增加普通消息发送，应优先调用用户可见的 QQ 原生分享/发送 UI 或已授权的公开 API，并显示目标会话、内容、确认和取消选项，同时限制速率。

## 3. 本地预设存储与格式

黑壳中可见 SharedPreferences 线索：
- OvO_Presetelems
- elem_count
- OvO_elem_<n>
- OvO_content_<...>
- loadPresetElem / loadElemContentMap
- savePresetElem / saveElemContentMap / deleteElement
- importPresetElemsFromFile / loadPresetElemsFromFile / exportPresetElemsToFile

交换格式以 name 与 content 为关键字段。TNT 支持并导出如下兼容结构（package_name 是 TNT 可选扩展字段，旧导入器可忽略）：

    [
      {"name":"文本示例","content":"你好"},
      {"name":"卡片草稿","content":"{\"title\":\"Preview\"}","package_name":"example.app"}
    ]

静态字符串还包含黑壳的复合记录分隔符：

    *awa_pkg_name_pkg_content_split*

TNT 能识别简单的“名称 + 分隔符 + 内容”记录，但始终把结果作为数据，不执行导入内容。

## 4. 云预设目录

黑壳 APK 中存在旧服务主机名和路径的字符串，但字符串不能证明接口仍活跃、鉴权约定正确或该地址值得信任。TNT 不硬编码黑壳的旧云服务地址。

TNT 云目录由用户显式填写 HTTPS JSON URL，执行 GET 读取；不跟随重定向、不附带 QQ Cookie/账号、不上传本地预设；检查响应大小、私网/本机解析和数据结构。用户预览并勾选后才导入。本地内容是“不可信数据”，不会当作代码、脚本或发包指令执行。

推荐数据字段：
- id：TNT 本地唯一标识
- name：预设名称
- content：原始模板内容
- package_name：可选来源/应用标签
- updatedAt：本地更新时间
- tags / description / schemaVersion：未来可添加的分类、说明和格式版本

## 5. 从黑壳新增类中筛选的移植方向

黑壳独有类集合包括以下方向。类级差异不等于同名类的方法实现完全不同。

| 黑壳方向 | TNT 的建议 | 注意事项 |
|---|---|---|
| LocalCardPresetManager / Ark 模板 | 扩展本地模板分类和标签 | 先验证 JSON，不声称一定能被 QQNT 发送 |
| CopyPictureLink / PictureMessageTools | 长按当前消息复制可见图片链接 | 区分原图/缩略图，不默认外传 |
| DouyinVideoParser / DirectVideoCardSender | 链接识别、来源展示、系统分享预览 | 不绕过平台访问控制 |
| QqMusicSongPicker | 生成分享草稿并调用系统分享 UI | 需考虑版权与服务条款 |
| MessageEmojiLikesMenu | 本地表情收藏和复制 | 不伪造协议反应 |
| LocationMessageMenu | 仅对用户明确选择/消息可见位置提供操作 | 默认不采集或上传定位 |
| AppTreasureSearch / SubIdCatalogUi | 本地应用 ID 目录和用户自定义数据源 | 不访问未授权数据、不复用可疑 Token |

## 6. 原版 QQ 与公开 API 的边界

普通 QQ Android 客户端私有实现、Xposed/LSPosed 模块和 QQ 开放平台 Bot API 是不同层级：
- QQNT 内部类/协议不是公开稳定 SDK，可能随版本变化。
- QQ Bot API 提供开放平台场景的消息与事件接口，权限、时效和发送频次受平台规则约束。
- 开放平台 Ark JSON 不等于客户端私有 Ark/RAW 二进制或协议。
- 安卓系统文件选择器、剪贴板、分享 Intent 与系统浏览器是较稳定的原生能力；客户端私有 Hook 需要按 QQ 版本单独测试。

参考：
- [ONO 上游源码](https://github.com/cwuom/ono)
- [QQ 机器人文档仓库](https://github.com/tencent-connect/bot-docs)
- [QQ 机器人发送消息接口](https://github.com/tencent-connect/bot-docs/blob/main/docs/develop/api-v2/server-inter/message/send-receive/send.md)

## 7. 测试清单

- [ ] 记录 Android 版本、QQ 精确版本、设备 ABI 和 Xposed/LSPosed 版本。
- [ ] 导入空文件、超限文件、错误 JSON、数组/对象/分隔符预设。
- [ ] 云目录的非 2xx、重定向、超大响应、私网 DNS 解析均被阻止。
- [ ] 云目录只预览，不自动导入或执行内容。
- [ ] 每个 QQ 内部 Hook 单独启停，失败不阻止设置页打开。
- [ ] 新功能不上传账号、Cookie、会话记录或定位信息。
- [ ] Release 使用自己的签名密钥并保留 GPL-3.0 与上游修改说明。
