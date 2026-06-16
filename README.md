# BayMcWhiteList

BayMcWhiteList 是一个 Paper/Folia 白名单插件。登录服允许玩家输入邀请码完成验证，主服和其他受保护子服会在预登录阶段查询 MySQL，没有白名单记录的玩家会被拒绝加入。

## 环境要求

- Paper API: `26.1.2`
- Java: `25+`
- 数据库: MySQL
- 构建: Gradle wrapper

## 构建

```powershell
.\gradlew.bat clean build
```

构建产物：

```text
build/libs/BayMcWhiteList-1.0-SNAPSHOT.jar
```

该 jar 已通过 Shadow 打入 HikariCP 与 MySQL Connector/J。

## 部署模式

登录服配置：

```yaml
server:
  name: "login"
  mode: "login"
```

主服/子服配置：

```yaml
server:
  name: "survival-1"
  mode: "protected"
```

`login` 模式下，玩家可以进入服务器并使用：

```text
/whitelist BAYMC-XXXXXXXX
```

`protected` 模式下，玩家加入前会查询 MySQL。没有白名单记录或数据库不可用时都会拒绝加入，避免主服误放人。

## 邀请码规则

默认格式：

```text
BAYMC-XXXXXXXX
```

后缀由以下内容签名生成：

```text
HMAC-SHA256(secret, playerKey + ":" + issueDate + ":" + prefix)
```

验证时会检查今天和之前 `valid-days - 1` 个自然日。默认 `valid-days: 7`，也就是签发当天到第 7 个自然日仍有效，第 8 个自然日失效。

## 关键配置

所有服务器必须使用相同的：

```yaml
code:
  prefix: "BAYMC"
  secret: "CHANGE_ME_TO_A_LONG_RANDOM_SECRET"
```

生产环境必须修改 `secret`。如果网站、机器人也要生成邀请码，它们需要使用同一个 `secret`、`prefix`、`timezone` 和玩家标识规则。

玩家标识：

```yaml
player:
  id-type: "uuid"
```

- 正版/online-mode 网络建议使用 `uuid`
- 离线或外置登录网络可以改为 `name`

## 命令

玩家：

```text
/whitelist <邀请码>
```

管理员：

```text
/baymcwhitelist generate <玩家名|UUID>
/baymcwhitelist status <玩家名|UUID>
/baymcwhitelist remove <玩家名|UUID>
/baymcwhitelist reload
/baymcwhitelist info
```

UUID 模式下，`generate <玩家名>` 只能给当前在线玩家生成；如果玩家不在线，可以直接传 UUID。

## 语言文件

所有玩家可见提示都在：

```text
plugins/BayMcWhiteList/lang/zh_CN.yml
```

语言格式使用 MiniMessage，不使用 `&a` 颜色码。

## 数据库

插件启动时会自动创建：

```text
baymc_whitelist_players
baymc_whitelist_logs
```

表名前缀由：

```yaml
storage:
  mysql:
    table-prefix: "baymc_"
```

控制。
