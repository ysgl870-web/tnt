# TNT · 光流 QQNT Android 模块

**二次开发：亦是光流**  
**基础工程：ONO（GPL-3.0）**  
**源码分支：`tnt-next/ono-base`**

TNT 是从 ONO 独立分支重新构建的 QQNT Android Xposed/LSPosed 模块。此分支以 `cwuom/ono` 的公开源码提交为基础，不继承旧 TNT 的功能实现；旧仓库 `main` 分支保持独立，不会被本分支改写。

## 主要方向

- 保留 ONO 的 QQNT 模块加载、设置和 Hook 基础架构。
- 增加 QQ 控件树探测器与 Scheme/WebView 链接记录。
- 使用模块内可见入口和本地记录，不要求悬浮窗权限、无障碍权限或远程服务。
- 不同 QQ 构建缺失某个可选 Hook 入口时自动跳过，不阻断 QQ 正常操作。

## QQ 界面控件探测器

在 TNT 设置 → 开发者选项 → 开启「QQ界面控件探测器」后，QQ 页面右侧会出现「控件」入口。点击进入页面检查模式：

- 绘制当前页面控件边框和序号。
- 递归扫描当前 Activity 的可见 View 树，最多 420 个节点。
- 按控件屏幕面积从小到大排序，列表显示前 180 项。
- 点选控件后显示完整类名、资源 ID、屏幕矩形坐标、宽高、面积、层级深度、透明度、可点击/长按/聚焦状态和子控件数量。
- 支持复制单个控件结构或整页结构 JSON。
- 文本默认隐藏；只有在点选控件后再单独选择“读取此控件文本”并二次确认才显示。EditText 和密码转换文本不会读取。
- 不触发控件点击、不修改控件监听器、不调用 QQ 私有动作。

## QQ 链接记录

开启控件探测器后，同步启用本地链接观察：

- Activity 启动时携带的 HTTP(S) / QQ Scheme 链接。
- Android WebView、腾讯 X5 WebView、QQ 已知 QmX5Webview 的 loadUrl / postUrl。
- WebView 页面跳转回调。
- 选中 TextView 时读取其中 URLSpan。
- 最新 100 条链接保存在本机 MMKV，可在「查看QQ链接记录」中查看、复制和清空。

查询参数中名称类似 token、cookie、auth、ticket、sign、skey、uin、session、password 的值会自动脱敏。模块不读取 HTTP 请求头、请求/响应正文、聊天消息包、TEA/TLV/ECDH 密钥或账号登录材料，不上传抓取记录。

## 参考项目与边界

- [ONO](https://github.com/cwuom/ono)：源码基础，保留 GPL-3.0 许可及版权声明。
- [QQHook](https://github.com/callng/QQHook)：研究协议分析工具组织方式与版本兼容说明；本工程没有移植登录密钥、认证签名或协议注入逻辑。
- [QEdge](https://github.com/LengYu-cr/QEdge-main)：研究 NT QQ Hook 加载、模块化功能入口和兼容性处理思路；未直接复制其第三方实现代码。

## 构建

需要 JDK 17、Android SDK Platform 35，并确保 Git 子模块可以递归拉取。

```bash
git clone --recurse-submodules https://github.com/ysgl870-web/tnt.git
cd tnt
git checkout tnt-next/ono-base
chmod +x gradlew
./gradlew --no-daemon assembleRelease
```

APK 输出名：`TNT-Module-<version>.apk`。如未提供发布签名密钥，沿用 ONO 的 PR 构建策略使用 Debug 签名；正式发布请配置你自己的签名密钥。

## TNT 构建中心

选择「源码构建」：

- 仓库：`https://github.com/ysgl870-web/tnt`
- 分支：`tnt-next/ono-base`
- 构建命令：`./gradlew --no-daemon assembleRelease`
- JDK：17
- Android SDK：Platform 35

## 版本适配

你提供的截图对应 **QQ 9.3.80.42775（官版渠道）**。控件树探测使用 Android View API，不依赖某个特定 QQ 混淆类名；链接记录另外适配 Android WebView、腾讯 X5 与已知 QmX5Webview 入口。该版本号是本轮实机适配目标，不代表已经完成真实设备验收。

## 署名

TNT · 光流 QQNT Android 模块  
二次开发：亦是光流  
基于 ONO 开源项目二次开发，遵循 GPL-3.0。
