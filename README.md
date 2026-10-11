# VAutoStop

Velocity 插件：**子服没人了就自动让 MCSManager 面板把实例停掉**。

群组服往往同时挂着七八个子服——小游戏、空岛、星空谷这类小服常年没人在线，
却一直占着内存。这个插件定期看一眼各子服的人数，**连续两次**都读到没人 →
调 MCSManager 面板把那个实例停掉，省下来的内存留给真正有人在玩的服。

- 只装 Velocity 代理端，**子服一个插件都不用装**（人数是代理管的，停机是面板执行的）
- 配置是 TOML，改完 `/vautostop reload`，**不用重启代理**
- 要盯哪些服：白名单 / 黑名单二选一，跟着 `velocity.toml` 的服名走
- 停机前会再做三重确认：确认面板上确实是「运行中」→ 再数一遍人数 → 才发请求（详见「安全设计」）

---

## 一、装

1. 把 `VAutoStop-<版本>.jar` 丢进 Velocity 的 `plugins/`
2. 启动一次代理，让它吐出 `plugins/vautostop/config.toml`
3. 改配置（至少填面板地址/守护 uuid/密钥 + 各子服的实例 uuid）
4. `/vautostop reload`

Velocity 要求 Java 17+。产物是 class 61，Velocity 3.4+ ~ 4.x 都能加载。

---

## 二、配置（config.toml）

```toml
enabled = true

interval-minutes = 10        # 多久查一次人数（1 ~ 1440）
required-empty-checks = 2    # 连续几次读到 0 人才动手（最少 2 次，写 1 也按 2 算）

[filter]
mode = "blacklist"                        # blacklist = 列的都不盯；whitelist = 只盯列的
servers = ["lobby", "survival"]           # 大厅和主生存别自动关

[mcsm]
enabled = true
url = "http://面板地址:2041"
daemon-id = "守护uuid"                     # 面板 → 守护进程 → 地址栏那串
apikey = "${env:MCSM_KEY}"                # 走环境变量，别写明文
timeout-ms = 8000
verify-running = true                     # 停之前先查一次面板状态，只对「运行中(3)」下手
dry-run = false                           # true = 只写日志、不真的停

[safety]
startup-grace-minutes = 3     # 代理起来/reload 后的前几分钟只记不算账
retry-cooldown-minutes = 5    # 同一个服尝试过一次后，隔这么久才再试

[servers.killer]
uuid = "实例uuid"              # 面板 → 打开实例 → 地址栏那串 32 位十六进制

[servers.bedwars]
uuid = "实例uuid"
enabled = false               # 配着但先停用
```

### 白名单 vs 黑名单

| | `mode` | servers 空着算什么 | 什么时候用 |
| --- | --- | --- | --- |
| 黑名单 | `blacklist` | **盯所有子服**（危险：会连大厅一起盯上） | 只想排除少数几个服 |
| 白名单 | `whitelist` | **谁都不盯**（安全） | 只想关某几个服 |

服名写 `velocity.toml` 里 `[servers]` 下的那个名字，大小写不敏感；
`[servers.xxx]` 的 `xxx` 保持一致，否则插件不知道该停面板上哪个实例 → 只会在日志里提醒。

### 环境变量

`${env:名字}` 会被换成系统环境变量的值，`${env:名字:-兜底}` 没设置时用兜底值。
**改环境变量要重启代理**（`reload` 不会重读环境）。取不到且没写兜底 → 后台告警，该项按空处理。

---

## 三、命令

全部需要 `vautostop.admin` 权限（控制台自带）。主命令 `/vautostop`，别名 `/vas`。

| 命令 | 作用 |
| --- | --- |
| `/vautostop` / `/vautostop list` | 看在盯哪些服、每服现在几个人、连续空了几次、上次动作是什么 |
| `/vautostop check` | 立刻跑一轮巡检（跳过宽限期/冷却，但**连续次数照算**） |
| `/vautostop test` | 逐个子服查一次面板实例状态 —— 验证 url / uuid / 密钥对不对 |
| `/vautostop reload` | 重读 config.toml 并按新间隔重新排期 |
| `/vautostop version` | 版本号 |

第一次上线建议：`dry-run = true` → `/vautostop test` 确认面板通 → `/vautostop list` 确认盯对了服
→ 观察一轮日志 → 再改回 `false`。

---

## 四、安全设计（为什么不会乱关服）

停机是个危险动作，所以每一步都有兜底：

1. **连续两次才动手** —— 一次采样说明不了"真没人"：玩家可能刚好在换服，这一瞬间原服和目的服都是 0 人。
   `required-empty-checks` 写 1 也会被强制成 2；想更保守就写 3、4 次。
2. **先看面板再说** —— `verify-running = true` 时，只有实例确实是「运行中(3)」才会发停止指令。
   已经停的 / 正在启动的 / 正在停止的，都直接跳过，不会每 10 分钟去怼一次。
3. **发请求前再数一遍人数** —— 判空到发请求之间隔着一次 HTTP，这段时间可能有人进来；
   第二次读到非 0 就立即放弃这一轮。
4. **uuid 不像 32 位十六进制 → 跳过** —— 配置还是占位值时永远不会真的关掉任何东西。
5. **启动宽限期** —— 代理刚起来/reload 后的前 N 分钟不动手（各后端可能还在拉起来）。
6. **重试冷却** —— 同一服两次停机尝试至少隔 N 分钟，面板抽风时不会每轮都重试。
7. ** `[servers.x] enabled = false`** —— 配着但不用。
8. **dry-run** —— 走完所有判断、日志里写清楚"本会调用面板停机"，但不真的发请求。
9. **读失败保留旧配置** —— config.toml 写挂了继续用上一次那份，不会变成半成品。

### 想验证会不会误关

盯日志里的这几行就够了：

```
[vautostop] 受监控子服：killer, bedwars, minigame, starvalley
[vautostop] killer 空了第 1/2 次，还没到连续次数，继续观察
[vautostop] killer 面板上不是「运行中」（停止），不用停
[vautostop] 正要停 killer 时它又有 2 人了，取消这次关机
[vautostop] killer 已请求面板停机（HTTP 200，面板状态码 停止中）
[vautostop] [dry-run] killer 已满足停机条件，本会调用面板停机（mcsm.dry-run=true，未真正发送）
```

---

## 五、面板对接

直连当前版本 MCSManager 的两个接口（路径与认证方式写死，不做老版本兼容）：

| 用途 | 接口 |
| --- | --- |
| 停止实例 | `GET /api/protected_instance/stop?uuid=<实例>&daemonId=<守护>` |
| 查实例状态 | `GET /api/instance?uuid=<实例>&daemonId=<守护>` |

鉴权按官方文档：apikey 拼在 **URL 参数**上，另外带两个**必需**请求头
`X-Requested-With: XMLHttpRequest`、`Content-Type: application/json; charset=utf-8`
（缺了面板一律 403，不只是拒绝），外加 `Authorization: Bearer` 作冗余。

状态码：`-1 忙碌 / 0 停止 / 1 停止中 / 2 启动中 / 3 运行中`。

⚠ apikey 进了 URL → 插件后台日志和命令输出里的地址都会自动把密钥替换成 `***`。
（注意：面板 URL 里带 `&`，所以 failed 请求的地址只进日志，不发给玩家，否则会被当颜色码吃掉。）

---

## 六、本地构建

```bash
JAVA_HOME=<JDK17+> mvn -B -o package     # 产物 target/VAutoStop-<版本>.jar
```

离线自测（不需要 Velocity）：

```bash
JDK=<JDK>/bin
"$JDK/javac" -encoding UTF-8 -cp target/classes -d target/test-classes tests/VasTest.java
"$JDK/java" -cp "target/classes;target/test-classes" cn.shijiu.vautostop.VasTest
```

版本号只在 `pom.xml` 改一处（`BuildConstants.VERSION` 由 `templating-maven-plugin` 生成，
因为 `@Plugin(version = ...)` 只能吃编译期常量）。

---

## 七、代码结构

| 文件 | 职责 |
| --- | --- |
| `VAutoStop.java` | 主类：装载/卸载、命令注册、配置重载与体检报告 |
| `VasConfig.java` | config.toml 的内存快照 + 白黑名单判定 + `${env:}` 展开 |
| `IdleMonitor.java` | 巡检调度、连续空服计数、停机链路（本项目的核心） |
| `McsmApi.java` | 面板 URL 拼装、请求头、响应解读、密钥打码 |
| `HttpService.java` | 异步 HTTP（JDK 自带 HttpClient，绝不占主线程） |
| `Toml.java` / `Json.java` | 零依赖的极简解析（两者都 fail-safe） |
| `AdminCommands.java` | `/vautostop` 的子命令 |
| `Permissions.java` | `vautostop.admin` |

MIT License · 拾玖世界
