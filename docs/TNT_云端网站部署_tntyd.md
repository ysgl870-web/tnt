# TNT 云端网站部署（ysgl.bot.cd/tntyd）

本目录对应的网站部署目标：`https://ysgl.bot.cd/tntyd/`。

## 部署
1. 在宝塔网站根目录创建 `tntyd` 目录。
2. 将发布包中的 `cloud-site/` 内容解压到该目录，确保 `index.php`、`api.php`、`bootstrap.php` 位于 `tntyd/` 根层级。
3. PHP 8.1+，启用 `pdo_sqlite`、`sqlite3`、`openssl`、`mbstring`。
4. 确保 `tntyd/data/` 由 PHP-FPM 用户写入，建议权限 750。
5. Nginx 站点配置增加：

```nginx
location ^~ /tntyd/data/ {
    deny all;
    return 403;
}
```

6. 在服务器执行 `openssl rand -hex 24 > tntyd/data/install.key`，确保 PHP-FPM 可读；访问 `https://ysgl.bot.cd/tntyd/install.php` 初始化管理员。初始化成功会删除一次性密钥。
7. 模块云端 API 地址填写：

`https://ysgl.bot.cd/tntyd/api.php`

## 子目录兼容与安全
- 页面/API 使用相对路径。
- Session Cookie Path 自动限定为当前安装目录（`/tntyd/`），避免与主站会话冲突。
- `data/` 禁止 Web 访问；禁止公开数据库、配置、安装密钥及备份。
- 更新时备份 `data/`，不要覆盖 `config.php`、SQLite 数据库和安装锁。

## 隐私
使用统计默认关闭。只有用户在模块内明确授权后才上传 QQ 号与 TNT 工具页前台停留时间；不上传聊天内容、好友列表、Cookie 或定位。用户可以撤销授权并删除统计数据。
