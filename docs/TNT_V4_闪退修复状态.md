# TNT V4 修复状态

日期：2026-10-29  
二次开发作者：亦是光流

## 本次修改

- 工具入口 Activity 和预设工坊迁移为 ONO 的 `BaseActivity`，使用 `doOnEarlyCreate` / `doOnCreate` 生命周期桥接。
- 工具首屏初始化失败时展示安全模式页面并记录异常。
- 工具页改成蓝色品牌头卡、双列工具卡片、分组、扩大触控区域和纵向滚动。
- 预设工坊同步采用宿主兼容生命周期和安全回退页。
- 设置页的工具中心/预设入口添加启动异常记录和提示。
- 设置项显示名统一为“光流工具中心”。

## 已执行的检查

- `kotlinc` 解析器检查新增 Kotlin 源文件：没有报告 `expecting` / 语法结构错误；因为没有 Android、Material 和 ONO classpath，完整编译会因大量预期的未解析 Android/项目符号而失败。
- 源码叠加脚本在模拟 ONO 文件结构上连续执行两次，均完成；修改的 Manifest 与 preference XML 均可解析。
- ZIP 完整性与 SHA-256 已检查。

## 尚未验证

- 当前执行环境没有可用 Android SDK、Gradle/Android Gradle Plugin 依赖缓存或连接设备，因此没有生成已签名的 APK。
- 需要在真实 ONO checkout 上用 JDK 17 和 Android SDK Platform/Build Tools 35 执行 `./gradlew --no-daemon assembleRelease`。
- 尚未取得用户设备的 `FATAL EXCEPTION` Logcat；BaseActivity 生命周期桥接是基于上游结构的修复方向，但真实 QQ 内仍需测试。

本 ZIP 是源码叠加包，不是可安装 APK。
