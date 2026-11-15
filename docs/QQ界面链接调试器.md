# QQ 界面链接调试器

TNT 内置的本地调试工具，适合分析 QQ 页面跳转与部分 HTTP 请求的**元数据**。

## 功能

- 记录 QQ 通过 Activity 发起的 HTTP(S) 与 QQ Scheme 跳转。
- 记录 Android WebView、腾讯 X5 WebView、QQ QmX5Webview 的 URL 加载。
- 可选探测 OkHttp 的 `OkHttpClient.newCall(Request)`，只记录请求方法与 URL。
- 最新 100 条记录保存在 TNT/QQ 共用的本地 MMKV 存储中。
- 从 TNT 设置页手动查看、复制或清空记录。

## 隐私与安全边界

- 不采集 HTTP 请求头、Cookie、Authorization、请求正文或响应正文。
- 不解析聊天消息、群成员私聊内容、登录协议、TEA/TLV/ECDH 密钥或 QQ 协议帧。
- URL 的所有查询参数值都会被替换为 `[redacted]`；URL 用户信息及疑似长 token 路径段会隐藏。
- 不包含远程服务器地址，不会联网上传抓取记录。
- 开启后可能增加少量 Hook 开销；不使用时关闭即可。

## 启用方式

1. 在 LSPosed 中确保 TNT 对 QQ 的作用域已启用。
2. 打开 TNT 设置 → 开发者选项 → 开启「QQ界面链接抓取」。
3. 在「开发者选项」中点击「查看QQ抓取记录」。
4. 可复制本地记录用于自己分析，也可以随时清空。

## 兼容性说明

当前适配代码依赖 Android Activity、Android WebView、腾讯 X5 WebView、QQ 的 `QmX5Webview` 已知入口，以及可选的 OkHttp 公共 API。不同 QQ 构建可能变更内部 WebView 包装类或网络栈，因此未命中的采集入口会自动跳过，不会阻断 QQ 功能。

静态检查目标参考 Android QQ 9.3.80（2026-10/11 版本记录）。这不是对所有设备/灰度包均已实机验证的承诺。请在目标设备上检查 TNT Hook 日志与实际采集结果。
