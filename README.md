# TNT 模块（QQNT 增强）

**二次开发作者：亦是光流**  
**上游项目：[cwuom/ono](https://github.com/cwuom/ono)**  
**许可证：GPL-3.0**

TNT 基于 ONO 的公开源码继续开发，保留上游及贡献者的版权、许可证与免责声明。当前目标为 Android 10+ 的 QQNT 环境，实际兼容性以具体 QQ 版本和 Xposed/LSPosed 加载器测试结果为准。

## TNT 新增能力

- TNT 启动工具页：快捷文案、JSON 校验/美化、链接/Scheme 分析、文本字节统计、Emoji 复制和环境诊断。
- 预设工坊：本地创建/编辑/删除/搜索、黑壳/AWA JSON 兼容导入导出、分隔符记录识别、HEX/Base64 工具。
- 云预设：用户手动输入 HTTPS JSON URL，GET-only、限制响应大小、不跟随重定向、检查私网解析；预览确认后才写入本地。
- Ark Bot JSON 离线草稿构建器：用于公开 QQ Bot API 格式草稿，不代表 QQNT 私有协议格式。
- 黑壳/AWA 代码差异和移植研究文档。

## 工具中心闪退修复与 UI

- [工具中心闪退修复与 UI 优化说明](docs/工具中心闪退修复与UI优化.md)
- [V4 修复验证状态](docs/TNT_V4_闪退修复状态.md)

V4 将工具页与预设页迁移到 ONO 的 `BaseActivity` 生命周期桥接，并增加安全回退界面和双列卡片式 UI。当前仍需真实 Android/QQ 环境完成最终构建和安装验证。

## 本地构建

需要 JDK 17 和 Android SDK Platform 35 / Build Tools 35：

    ./gradlew --no-daemon assembleRelease

APK 输出名：TNT-Module-<version>.apk。Release 默认签名配置需由使用者检查；使用自己的签名密钥发布。修改上游工程后请先执行 git diff 并检查新增权限。

## 黑壳发包与预设研究

- [云预设目录格式与安全说明](docs/云预设目录格式与安全说明.md)
- [云预设示例 JSON](examples/cloud-presets.example.json)

- [黑壳/AWA 发包工作台与预设机制](docs/TNT_黑壳预设与发包机制研究.md)
- [TNT V3 使用与构建说明](docs/TNT_V3_使用与构建说明.md)

## 发包功能的设计边界

TNT 将预设作为数据处理，支持预览、验证、格式化和导入导出。当前版本没有任意 RAW/协议注入、伪造 UIN/Token/签名或循环重复发送。普通聊天功能优先走可见的 QQ 原生 UI 或官方已授权接口。

## 上游说明

本项目是基于 GPL-3.0 工程的衍生开发版本。分发时应保留上游授权信息、标注变更，并按 GPL-3.0 提供相应源代码。作者署名仅描述 TNT 二次开发工作，不替代上游代码及其他贡献者的版权。
