# TNT · 光流工具箱

**二次开发作者：亦是光流**  
**上游项目：[cwuom/ono](https://github.com/cwuom/ono)**  
**项目仓库：[ysgl870-web/tnt](https://github.com/ysgl870-web/tnt)**  
**许可证：GPL-3.0**

TNT 是基于 ONO 公开源码继续开发的 QQNT Android 模块。项目保留上游及贡献者的版权、许可证与免责声明；亦是光流的署名仅说明 TNT 二次开发工作，不替代上游版权。

## 项目方向

- QQNT 使用体验优化与模块化扩展。
- 常用本地工具、预设管理与开发诊断。
- 统一、清晰、适配手机的操作界面。
- 尽量减少隐式网络行为；涉及分享时使用 Android 可见系统分享面板。

## 主要功能

### QQNT 模块能力

现有源码包含聊天与消息、开发者辅助、娱乐、资料卡、Sigma 和 BugFix 等功能。当前模块类清单包括：

- 聊天：Bilibili 自动转播放卡、快捷菜单、聊天滚动记忆、撤回相关处理、消息加密配置、小程序跳转、气泡重定向、自消息处理、贴纸入口。
- 开发者：Cookie/Bkn 工具、VConsole 注入、Scheme 跳转、Hook Codec、消息查询、PacketHelper 入口、QSign Hook。
- 娱乐：文本过滤、好友 UIN 选择、群主显示、文本修改、表情响应等。
- 资料卡与 Sigma：资料卡菜单、消息追踪、昵称预测。
- BugFix：Markdown、QQ 崩溃和长文本显示修复。

具体兼容性与行为以目标 QQ 版本及 Xposed/LSPosed 实机测试为准。

### TNT 工具台

- 搜索、分类筛选和长按收藏。
- AWA/黑壳预设工坊：本地创建、编辑、删除、搜索、批量导入/导出与云目录预览。
- JSON 美化/校验及递归字段差异比较。
- QQ Scheme/URL 本地分析、URL/Base64 编解码。
- 文本、Unicode 代码点与 UTF-8 字节统计。
- SHA-256 本地摘要。
- 快捷文案保存与复制。
- Android 系统分享助手。
- 环境诊断：Android/API、ABI、QQ 版本及模块版本。

### 预设兼容

TNT 与 AWA/黑壳预设库的核心交换字段为 `name` 和 `content`，可选 `package_name`。同时兼容黑壳历史分隔符：

```text
*awa_pkg_name_pkg_content_split*
```

预设内容被视为不可信文本/JSON，不会在导入后当作脚本执行。

## AWA / 黑壳研究

仓库 docs 中提供 APK 静态逆向研究与 TNT 功能映射总结。研究材料将静态确认、功能推断与未验证运行行为分开说明。

**重要边界：** TNT 不移植任意 RAW/协议注入、伪造 UIN/Token/签名或循环重复群发。不会把 APK 中发现的硬编码凭证或未验证第三方接口当作可复用功能。普通分享优先通过 Android 系统分享面板或公开授权接口。

## 本地构建

需要 JDK 17 与 Android SDK Platform 35 / Build Tools 35：

```bash
./gradlew --no-daemon assembleRelease
```

APK 输出名：`TNT-Module-<version>.apk`。Release 签名由构建者自行配置；发布前应确认签名密钥一致。修改上游工程后请检查 `git diff`、新增权限和依赖来源。

## 作者描述

TNT（光流）QQNT Android 模块，专注使用体验优化、实用工具与模块化扩展。二次开发作者：亦是光流。基于 ONO 开源项目，遵循 GPL-3.0 并保留上游及贡献者版权声明。

