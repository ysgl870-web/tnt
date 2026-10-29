# TNT 修复分支

仓库：https://github.com/ysgl870-web/tnt  
分支：tnt-tool-center-crashfix-v4  
作者：亦是光流

此分支针对“点击光流工具中心闪退”和手机 UI 拥挤进行了修复：
- 工具中心和预设工坊迁移到 ONO 的 BaseActivity 生命周期桥接。
- 首屏初始化失败时显示安全模式和异常信息。
- 工具中心使用双列卡片布局、蓝色品牌头卡、分组和整页滚动。
- 设置页入口增加启动异常日志。
- 添加修复说明和测试状态文档。

仍需使用真实 Android SDK/JDK 17/Android SDK Platform 35 构建，并在用户实际 QQ 版本和 Xposed/LSPosed 环境中验证。未生成已签名 APK，也未声称真机测试通过。
