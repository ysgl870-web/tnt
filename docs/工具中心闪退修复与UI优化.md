# 光流工具中心闪退修复与 UI 优化

作者：亦是光流

## 排查结果

现有工具入口由 ONO 设置页打开，而 ONO 的设置页本身继承 `BaseActivity`。这个基类不仅是 AppCompat 封装，还负责宿主 QQ 内的 Activity 生命周期转接（StartupDirectorBridge / 宿主初始化路径）。

早期新增的 TNT 工具页直接继承 `AppCompatTransferActivity` 并重写普通 `onCreate`。当它从 QQ 内的模块设置页启动时，没有复用同样的宿主生命周期桥接方式；这是一个高风险且与既有 ONO 设置页不一致的入口。没有用户设备的 FATAL EXCEPTION 日志，所以这是根据上游生命周期实现得到的修复方向，不应写成已由真实崩溃日志证明的唯一原因。

## 修复

- `TNTLauncherActivity` 改为继承 `BaseActivity`。
- 初始化入口迁移至 `doOnEarlyCreate` / `doOnCreate`，并在早期阶段设置 ONO 主题。
- 初始化异常写入 Android Log，并显示安全模式界面，不再在构建首屏期间直接退出。
- 点击工具失败时显示具体异常类型和消息，避免只看到无反馈的闪退/无响应。
- `TNTPresetStudioActivity` 使用同一宿主生命周期桥接，并加入独立安全模式回退页。
- 设置页启动 TNT 工具与预设页时增加同步异常捕获和日志记录。

## UI 改动

- 蓝色品牌头卡：显示“光流工具中心”、TNT 模块和作者信息。
- 常用工具改为双列卡片，标题/描述分层，手指点击区域更大。
- 统一卡片圆角、边框、留白和点击波纹。
- 改善窄屏的按钮拥挤，内容整体可纵向滚动。
- 异常时保留基础诊断入口。

## 验证边界

- 已对源码叠加脚本做两次连续应用测试，并检查新增 Activity、设置项和 XML 结构。
- Kotlin 源码进行了解析器级检查；由于没有 Android/ONO 依赖 classpath，出现预期的未解析 Android/模块引用，不能替代 Android Studio/Gradle 构建。
- 当前没有设备 Logcat，也没有安装测试，不声称已通过真实 QQ 的闪退验证。
- 如果修复后仍闪退，请提供从 `FATAL EXCEPTION` 到最后一个 `Caused by` 的 Logcat，以进一步确定具体错误。

## 上游

TNT 是基于 [cwuom/ono](https://github.com/cwuom/ono) GPL-3.0 源码的衍生开发；分发时保留上游许可和修改说明。
