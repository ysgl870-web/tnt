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

在 TNT 设置 → 开发者选项 → 开启「QQ界面控件探测器」后，QQ 页面右侧会出现「控件」入口。点开后提供独立的控件工具栏：

- 绘制可点击控件的细蓝色边框与序号，不铺全屏蓝色蒙层。
- 递归扫描当前 Activity 的可见 View 树，最多 420 个节点；控件列表最多显示前 160 项。
- **点选模式默认关闭**：工具栏显示「点选:关」时，触摸事件会交还给 QQ，正常点击不会被探测层拦截；主动开启「点选:开」后，点蓝色边框才会打开控件详情。
- 详情显示类名、资源 ID、屏幕坐标、宽高、面积、层级深度、透明度、点击/长按/聚焦/启用状态，以及控件自身链接和当前 Activity/WebView 页面链接。
- 支持复制单个控件结构或整页 JSON；链接可单独复制或保存到本地历史。
- 可对选中的小范围控件执行**临时隐藏**或**临时禁用**；工具栏「恢复控件」可还原。关闭探测器或停用功能时也会自动还原，避免把 QQ 页面留在异常状态。
- 工具栏与扫描操作均带异常保护；文本默认隐藏，只有明确选择并二次确认后才读取。EditText 和密码转换文本不会读取。

## QQ 链接记录

开启控件探测器后，同步启用本地链接观察：

- Activity Intent 携带的 HTTP(S) / QQ Scheme 链接，并观察 startActivity、startActivityForResult、onNewIntent 与 setIntent 跳转。
- Android WebView、腾讯 X5 WebView、QQ 已知 QmX5Webview 的当前 URL、loadUrl / postUrl 与页面跳转回调。
- 「保存链接」会重新扫描当前 Activity 和可见 WebView；找不到时会明确提示，不会虚报保存成功。
- 选中 TextView 时读取其中 URLSpan。
- **可见文本里的裸链接**：控件里没有 URLSpan、只是纯文本的链接，也会按协议正则解析出来（http/https/mqqapi/qqdocurl）。
- **Ark 卡片内链接**：卡片 JSON 里的 jumpUrl / qqdocurl / schema 会被单独抽出来归入链接列表。
- **消息级链接**：配合下面的「QQ 消息信息提取」，聊天消息正文与卡片里的链接也会进这里。
- 最新 **300** 条记录保存在本机 MMKV，可在「查看QQ链接记录」中查看、按类型筛选（含「只看图片直链」）、导出 JSON、复制和清空。
- 记录里 rkey / token / skey 等签名参数在**显示时打码**，点「复制完整内容」才给可用原值（图片直链必须带 rkey 才能打开）。

查询参数中名称类似 token、cookie、auth、ticket、sign、skey、uin、session、password 的值会自动脱敏。模块不读取 HTTP 请求头、请求/响应正文、聊天消息包、TEA/TLV/ECDH 密钥或账号登录材料，不上传抓取记录。

## QQ 消息信息提取（新增）

TNT 设置 → 开发者选项 → 开启「QQ消息信息提取」。与控件探测器的区别：**不再靠界面猜，直接读 QQ 已经加载在内存里的消息模型（MsgRecord）**。

开启后：

- **自动记录**：收发/浏览消息时自动提取，保留最近 **30 条**（存在模块自己的本机配置里，方便设置页跨进程查看；不写 QQ 的消息数据库、不联网，可一键清空）。
- **单条查看**：长按任意消息 → 菜单「提取」，弹出该消息的完整提取结果。
- **历史查看**：设置页「查看QQ消息提取记录」，可逐条查看、导出 JSON、一键复制全部图片直链。

能提取的内容：

| 类型 | 说明 |
| --- | --- |
| 图片直链 | `PicElement.originImageUrl` 拼 `https://multimedia.nt.qq.com.cn`，按会话类型自动补群/私聊 rkey，`appid=1406` 走群 rkey；没有 origin 时退化为 `gchat.qpic.cn` 的 md5 地址；表情图补 `emojiWebUrl` |
| Ark 卡片 JSON | `ArkElement.bytesData` 格式化成缩进 JSON；`StructMsgElement.xmlContent` 作为结构化 XML 单独列出 |
| 卡片内链接 | 卡片 JSON/XML 里按 key 语义（jumpUrl/qqdocurl/schema/url/href...）递归抽取 |
| 正文链接 | 文本与 Markdown 元素里的 http(s) / mqqapi / qqdocurl 链接 |
| 小程序 appid | 卡片里的 appid，便于追查跳转目标 |
| 界面消息 | 消息文本、发送者昵称/UIN、会话名、chatType、时间、msgId/msgSeq |
| 整条 JSON | 一键复制全部字段，方便喂给别的工具 |

结果对话框里「复制单项」可以分别复制：图片直链、Ark 卡片 JSON、结构化 XML、全部链接、appid、消息文本、整条 JSON；也可以直接打开第一张图片直链。

隐私边界：只读 QQ 内存中的消息模型，不读本地消息数据库文件、不抓网络包、不上传任何内容；提取结果只存在模块自己的本机配置里（最近 30 条），随时可在「查看QQ消息提取记录」→「清空」删掉。

## 参考项目与边界

- [ONO](https://github.com/cwuom/ono)：源码基础，保留 GPL-3.0 许可及版权声明。
- [QQHook](https://github.com/callng/QQHook)：研究协议分析工具组织方式与版本兼容说明；本工程没有移植登录密钥、认证签名或协议注入逻辑。
- [QEdge](https://github.com/LengYu-cr/QEdge-main)：研究 NT QQ Hook 加载、模块化功能入口和兼容性处理思路；本轮参考其「从消息元素取图片直链（originImageUrl + rkey）」「从 AIOMsgItem 取 ArkElement.bytesData 复制卡片 JSON」的做法，按本工程结构重写，未直接复制其第三方实现代码。

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

`tnt-next/ono-base` 已纳入 GitHub Actions：向该分支提交代码后会执行 Release 构建，并上传名为 `tnt-next-apk` 的 APK 产物（测试签名）。选择「源码构建」：

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


## 黑壳区：控件与消息提取

新增能力集中在设置的「黑壳区」分组：控件探测器、QQ 消息信息提取、链接记录与消息提取记录。控件悬浮入口打开后会显示可折叠的悬浮工具面板；控件详情可复制识别到的图片直链与 Ark JSON。链接记录按图片直链、卡片 JSON、跳转链接分类，相同内容合并并可展开每次捕获时间及上级页面链接。普通跳转会优先尝试交给 QQ / TIM 处理，找不到匹配入口时才回退系统默认应用。

消息提取只读取 QQ 已加载到内存的 `MsgRecord` / 可见 View 数据，记录保存在模块本机配置中；不读取 QQ 数据库文件、不读取请求头或 Cookie、不抓包、不上传。图片直链中的 rkey 属于有时效的访问签名，界面中会隐藏签名值，复制完整链接时保留原值。不同 QQ 版本的内部字段可能变化，个别消息类型可能无法提取。
