# BayMcWhiteList

Paper / Folia `26.1.2` 白名单插件,BayMcWhiteList 通过邀请码验证玩家并将白名单状态写入 MySQL,登录服负责验证,主服和受保护子服负责拦截未过白玩家

## 🔂 功能特性
- 支持玩家在登录服使用 `/whitelist <邀请码>` 完成白名单验证
- 支持受保护服务器在预登录阶段查询 MySQL,没有白名单记录的玩家会被拒绝加入
- 支持 `login` 登录服模式和 `protected` 受保护服务器模式,适合多端网络服部署
- 支持 `BAYMC-XXXXXXXX` 格式邀请码,前缀和后缀长度可配置
- 邀请码后缀使用 `HMAC-SHA256` 按玩家标识,签发日期和前缀计算生成
- 支持七天自然日有效期窗口,默认检查当天和前 `valid-days - 1` 天
- 支持 `uuid` 和 `name` 两种玩家标识模式,适配正版服和离线/外置登录网络
- 支持 MySQL 作为白名单状态唯一数据源
- 支持 HikariCP 连接池,并自动创建白名单记录表和审计日志表
- 支持管理员生成邀请码,查询状态,移除白名单,重载配置和查看运行信息
- 支持 MiniMessage 语言文件,所有玩家可见提示都可在 `lang/zh_CN.yml` 中配置
- 支持 Paper 和 Folia 调度模型,数据库操作在异步线程执行

## 🌋 运行环境
- Java `25+`
- Paper API `26.1.2`
- 推荐服务端版本:Paper / Folia `26.1.2`
- 数据库:`MySQL`
- 构建工具:Gradle Wrapper

插件依赖 HikariCP 与 MySQL Connector/J,发布 jar 会通过 Shadow 打包所需运行依赖

## 🖥️ 服务端支持

| 服务端 | 支持情况 |
|---|---|
| Paper 26.1.2 | ✅ 支持 |
| Folia 26.1.2 | ✅ 支持 |
| Leaf 26.1.2 | ✅ 支持 |
| Purpur 26.1.2 | ✅ 支持 |
| Pufferfish 26.1.2 | ✅ 支持 |
| Spigot | ❌ 不支持 |
| CraftBukkit | ❌ 不支持 |

其他服务端分支尚未完整测试,请自行研究测试

## 📦 安装
下载地址:[GitHub Releases](https://github.com/konsheng/BayMcWhiteList/releases)

- 在 Releases 的 Assets 中下载最新版 `BayMcWhiteList-*.jar`
- 确认服务端使用 Java `25+`,并运行 Paper / Folia `26.1.2`
- 安装或替换 jar 前先关闭服务端
- 将 `BayMcWhiteList-*.jar` 放入登录服和需要保护的主服/子服 `plugins` 目录
- 启动一次服务端,等待生成 `config.yml` 和 `lang/`
- 修改 `plugins/BayMcWhiteList/config.yml` 中的 MySQL 连接信息
- 按需修改 `plugins/BayMcWhiteList/lang/zh_CN.yml` 中的 MiniMessage 提示文本
- 将所有服务器的 `code.secret`,`code.prefix`,`code.timezone` 和 `player.id-type` 保持一致
- 登录服设置 `server.mode: "login"`,主服和受保护子服设置 `server.mode: "protected"`
- 修改配置或语言文件后执行 `/baymcwhitelist reload`,也可以重启服务端
- 给管理员或权限组分配需要的 `baymcwhitelist.*` 权限

登录服配置示例

```yaml
server:
  name: "login"
  mode: "login"
```

受保护服务器配置示例

```yaml
server:
  name: "survival-1"
  mode: "protected"
```

## ⌨️ 命令

主命令
- `/baymcwhitelist`
- 别名:`/bmwl`
- 玩家验证命令:`/whitelist`

参数约定
- `<>` 必填
- `[]` 选填

命令列表
- **`/whitelist <邀请码>`**<br>
  权限:`baymcwhitelist.use`<br>
  在登录服提交邀请码并写入 MySQL 白名单记录,需要游戏内执行
- **`/baymcwhitelist generate <玩家名|UUID>`**<br>
  权限:`baymcwhitelist.generate`<br>
  为指定玩家生成绑定邀请码,不直接写入白名单记录
- **`/baymcwhitelist status <玩家名|UUID>`**<br>
  权限:`baymcwhitelist.status`<br>
  查询指定玩家当前白名单状态和记录信息
- **`/baymcwhitelist remove <玩家名|UUID>`**<br>
  权限:`baymcwhitelist.remove`<br>
  移除指定玩家的白名单记录,并写入管理员操作日志
- **`/baymcwhitelist reload`**<br>
  权限:`baymcwhitelist.reload`<br>
  重载配置,语言文件和数据库连接
- **`/baymcwhitelist info`**<br>
  权限:`baymcwhitelist.info`<br>
  查看插件版本,服务器模式,邀请码配置,玩家标识模式和数据库状态

UUID 模式下,`generate <玩家名>` 只能给当前在线玩家生成;如果玩家不在线,可以直接传 UUID

## 🧩 邀请码创建提示词

需要让其他 AI 根据正版玩家名联网查询 UUID 并创建邀请码时,可以直接使用下面的提示词

```text
玩家ID: <填写玩家名>

联网查询该玩家的正版 Minecraft UUID,使用 Mojang 官方接口
https://api.mojang.com/users/profiles/minecraft/<玩家ID>

将返回的 32 位 UUID 转成标准 UUID 格式
xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx

邀请码配置
prefix = BAYMC
secret = <填写 code.secret>
suffixLength = 8
validDays = 7
timezone = Asia/Shanghai

创建规则
playerKey = 标准 UUID 字符串
issueDate = timezone 当前日期,格式 yyyy-MM-dd
payload = playerKey + ":" + issueDate + ":" + prefix
digest = HMAC-SHA256(secret 的 UTF-8 字节, payload 的 UTF-8 字节)
suffix = digest 的 RFC4648 Base32 无填充编码前 suffixLength 位
邀请码 = prefix + "-" + suffix

输出
玩家ID
UUID
签发日期
有效期至
邀请码
```

## 🔐 权限

- **`baymcwhitelist.use`**<br>
  默认:`true`<br>
  允许玩家使用 `/whitelist <邀请码>` 完成白名单验证
- **`baymcwhitelist.admin`**<br>
  默认:`op`<br>
  允许访问管理员命令入口和未知子命令帮助提示
- **`baymcwhitelist.generate`**<br>
  默认:`op`<br>
  允许为玩家生成绑定邀请码
- **`baymcwhitelist.status`**<br>
  默认:`op`<br>
  允许查询玩家白名单状态
- **`baymcwhitelist.remove`**<br>
  默认:`op`<br>
  允许移除玩家白名单记录
- **`baymcwhitelist.reload`**<br>
  默认:`op`<br>
  允许重载配置,语言文件和数据库连接
- **`baymcwhitelist.info`**<br>
  默认:`op`<br>
  允许查看插件运行信息

## 🛡️ 数据安全

- 白名单状态只写入 MySQL,不依赖原版白名单文件
- 受保护服务器在数据库不可用时会拒绝玩家加入,避免误放未知玩家
- 邀请码按玩家标识签名生成,不能被其他玩家共用
- 未使用的邀请码不会提前写入数据库,验证成功后才记录白名单状态
- 生产环境必须修改默认 `code.secret`,所有生成或校验邀请码的组件必须使用同一密钥
- MySQL 表名前缀会校验为安全字符后再用于 SQL 标识符
- 登录检查,验证结果和管理员移除操作会写入审计日志

## 🧾 审计

审计用于记录白名单验证,加入拦截和管理员操作,方便后续追溯和排查问题

- 记录玩家标识,玩家名,动作类型,邀请码,服务器名,IP 和时间
- 覆盖验证成功,重复验证,无效邀请码提交,受保护服务器拒绝加入和管理员移除
- 日志写入 MySQL 的 `baymcwhitelist_whitelist_logs` 表
- 白名单状态写入 MySQL 的 `baymcwhitelist_whitelist_players` 表

## 🛠️ 构建

Windows

```powershell
./gradlew.bat clean build
```

Linux

```bash
./gradlew clean build
```

本地产物

```text
build/libs/BayMcWhiteList-1.0.0-SNAPSHOT.jar
```

## 📄 许可证

本项目使用 GNU General Public License version 3,详情见 [LICENSE](LICENSE)

## 📊 bStats

[![bStats](https://bstats.org/signatures/bukkit/BayMcWhiteList.svg)](https://bstats.org/plugin/bukkit/BayMcWhiteList/32035)
