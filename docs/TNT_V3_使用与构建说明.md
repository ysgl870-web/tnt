# TNT 模块 V3：使用与构建

显示名称：TNT 模块  
二次开发作者：亦是光流  
上游：cwuom/ono（GPL-3.0）

## 当前叠加包包含

- TNT 工具中心：功能搜索、收藏、受限配置备份/恢复和设备诊断。
- TNT 预设工坊：本地创建/编辑/删除/搜索、JSON 校验/美化、HEX/Base64 转换、AWA/黑壳格式导入导出。
- 云目录读取：手动输入 HTTPS JSON URL；GET-only、不跟随重定向、不发送 Cookie、不上传本地数据，预览后才导入。
- Ark JSON 离线草稿构建器，输出 QQ Bot 开放平台格式的草稿，不等同 QQNT 私有协议。
- 黑壳新增功能移植路线，包括图片链接工具、媒体分享预览、表情收藏、位置操作和本地 App ID 目录。

## 应用叠加包

本次会话中的叠加 ZIP 包含 apply_tnt_overlay.py 和 overlay 源码。解压后执行：

    python apply_tnt_overlay.py /path/to/ono
    cd /path/to/ono
    git diff
    ./gradlew --no-daemon assembleRelease

先检查 git diff，并备份现有工程。构建环境需要 JDK 17、Android SDK Platform 35 和 Build Tools 35。在真实构建成功前，不要把源码叠加包称作 APK。

## 不会自动执行的内容

预设内容视为数据，不视为可执行脚本。V3 不包含任意原始协议包注入、循环/批量重复发包，也不会伪造 UIN、Token 或签名。对本地内容只做格式化、预览、校验和复制。

## 已知限制

- QQNT 内部 Hook 需要针对具体 QQ 版本实测。
- 静态 APK 对比不证明同名方法实现完全相同。
- APK 中出现的旧云服务域名不代表 API 仍开放或可信。
- 此叠加包仍需在 Android 工程、设备和 Xposed/LSPosed 环境中实际构建与验证。
