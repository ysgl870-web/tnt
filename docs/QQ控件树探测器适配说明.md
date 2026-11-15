# QQ 控件树探测器适配说明

## 目标界面

当前已知目标设备截图显示：

- QQ 版本：9.3.80.42775（官版渠道）
- 控件选择列表按面积从小到大排序
- 控件条目包含类名、坐标矩形、资源 ID、alpha 与 View 类型

TNT 使用 Android View 层级遍历实现通用探测，不需要在每个 QQ 版本内维护一份固定的 View 类名表。

## 控件采集字段

| 字段 | 说明 |
|---|---|
| class | View 的完整运行时类名 |
| resourceId | 资源 ID 名称；无 ID 时显示 NO_ID |
| bounds | 屏幕坐标 [left, top, right, bottom] |
| width / height / area | 可见矩形尺寸与面积 |
| depth | 在当前 View 树中的深度 |
| alpha | 当前 View alpha |
| clickable / longClickable | Android View 原生状态 |
| focusable / enabled | Android View 原生状态 |
| childCount | 子 View 数量 |
| links | WebView URL 或 TextView URLSpan（如存在） |

遍历最多收集 420 个可见节点，排序后供边框图层和列表使用；大列表最多呈现 180 项。节点覆盖层排除自身，避免递归扫描工具本身。

## 隐私约束

- TextView 文本默认不收集、不写入 JSON。
- 用户必须在详情中单独触发并确认后才能读取普通 TextView 文本。
- EditText 和密码转换文本不读取。
- URL 仅保存在本地 MMKV，敏感参数名对应的值会替换为 [redacted]。
- 不读取 Cookie、Authorization、HTTP headers、请求/响应 body 或聊天协议数据。
- 无外部上传地址、后台服务或自动分享逻辑。

## 兼容策略

- View 树扫描依赖 Android 公共 View API，不依赖 QQ 内部混淆方法名。
- Activity 生命周期通过 Application.ActivityLifecycleCallbacks 添加「控件」入口。
- 链接捕获为可选 Hook：Activity 跳转、Android WebView、腾讯 X5 WebView、QmX5Webview。
- 不同 QQ 版本缺少某个类/方法时跳过该入口，不能因调试功能导致宿主崩溃。
- 目标构建：QQ 9.3.80.42775。需要在目标设备安装后检查 LSPosed 激活、入口显示和链接命中情况。

## 验收清单

- [ ] LSPosed 启用 TNT 并选择 QQ 作用域
- [ ] 开启 QQ界面控件探测器
- [ ] QQ 会话页出现「控件」入口
- [ ] 覆盖层能绘制可见控件矩形
- [ ] 控件列表按面积升序
- [ ] 选中控件显示类名、资源 ID、bounds、状态字段
- [ ] 结构 JSON 可复制
- [ ] 普通 TextView 文本只有二次确认后读取
- [ ] EditText 和密码文本不会被读取
- [ ] QQ Scheme / WebView URL 能写入本地历史
- [ ] 关闭工具后覆盖层清除，QQ 原有操作恢复
