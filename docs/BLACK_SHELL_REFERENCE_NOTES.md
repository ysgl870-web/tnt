# TNT 黑壳区：开源项目调研与移植记录

更新时间：2026-11-17

本文记录黑壳区的功能设计参考。TNT 保持自身 HookItem、配置、运行进程和悬浮窗实现；以下项目用于理解公开功能和交互模式，不代表将其源码直接合并。

## 参考项目与适配判断

| 项目 | 可借鉴方向 | TNT 的处理 |
| --- | --- | --- |
| [QAuxiliary](https://github.com/cinit/QAuxiliary) | QQ 功能模块化、消息/卡片辅助、可复用设置组件 | 延续 TNT 的 HookItem 分组；为卡片、链接和控件提供统一的悬浮入口 |
| [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) | Hook 诊断、环境快照、崩溃/异常可观测性 | 新增本地页面诊断报告；MIUI/HyperOS 专用 Hook 不移植到 QQ |
| [qq-stub](https://github.com/LuckyPray/qq-stub) | QQ 类、接口与宿主版本差异的研究索引 | 用于定位和核对接口；不将整套 stub 类覆盖到 TNT 包空间 |
| [QwQ](https://github.com/fuqiuluo/QwQ) | QQ WebView、消息工具和 Hook 分层 | 仅采集当前可见 WebView 的公开状态；不对网页任意开放宿主 JS Bridge |
| [QEdge](https://github.com/LengYu-cr/QEdge-main) | QQ 内菜单、卡片复制、媒体入口和分组交互 | 保留已有卡片/链接能力，并加入快速查找和页面诊断入口 |

## 已落地内容

- **页面诊断**：展示当前 Activity、宿主包名及版本、进程、Android 版本、设备型号、屏幕与 Content 尺寸、当前可见控件统计，以及 WebView/X5 的 URL、标题和加载进度。可复制本机诊断报告。
- **控件快速查找**：悬浮窗支持按类名或资源 ID 搜索当前扫描结果，选中后可直接进入控件详情；搜索不会读取或匹配聊天文本。
- **悬浮入口**：以上工具可从黑壳区悬浮菜单直接打开，继续使用 TNT 原有面板的样式与触控反馈。

## 明确不移植的行为

- 不开放任意网页域名调用 QQ 内部 JavaScript Bridge。
- 不绕过 QQ 风险网页、安全校验或账号安全提示。
- 不静默拦截、丢弃或重定向 QQ 宿主日志。
- 不读取 Cookie、登录票据、网络请求/响应正文或聊天数据库。
- 不把 HyperOS/SystemUI 专用 Hook 当作 QQ 功能使用。

## 许可证与源码使用说明

TNT 仓库使用 GPL-3.0。调研时发现各参考项目的许可条件并不相同：

- QAuxiliary 的仓库根目录使用自定义通用许可协议，包含非商业使用及修改作品传播条件。
- HyperCeiler 的代码文件标注 AGPL-3.0-or-later。
- QwQ 的 README 标注 GPL-3.0。
- 当前检查的 qq-stub 与 QEdge-main 根目录未发现明确的 LICENSE 文件。

本轮新增功能是按 TNT 自身结构独立实现的行为和诊断界面，没有复制上述仓库的源代码片段，也没有将其依赖或完整类文件并入 TNT。后续如需复用任何项目的具体代码，必须先核实该文件的适用许可证、版权声明和兼容义务。
