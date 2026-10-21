# VAutoStop

https://github.com/shijiu-world/VAutoStop · MIT License · 拾玖世界

Velocity 插件：**子服没人了就自动让 MCSManager 面板把实例停掉**。

群组服往往同时挂着七八个子服——小游戏、空岛、星空谷这类小服常年没人在线，
却一直占着内存。这个插件定期看一眼各子服的人数，**连续两次**都读到没人 →
调 MCSManager 面板把那个实例停掉，省下来的内存留给真正有人在玩的服。

- 只装 Velocity 代理端，**子服一个插件都不用装**（人数是代理管的，停机是面板执行的）
- 配置是 TOML，改完 `/vautostop reload`，**不用重启代理**
- 要盯哪些服：白名单 / 黑名单二选一，跟着 `velocity.toml` 的服名走
- **一个子服可以配多个面板实例**（同一端口挂好几个实例、同时至多跑一个的用法）：
  停机前逐个查状态，只停那个真正在跑的
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

[log]
verbose = false               # false = 只在真的停机时打日志（见下节）

[servers.killer]
uuid = ["实例uuid", "另一个实例uuid"]   # 同端口互斥：逐个查谁在跑，就停那个

[servers.bedwars]
uuid = ["实例uuid"]                     # 只写一个也照样用列表
enabled = false                          # 配着但先停用
```

### 子服 → 实例：为什么 uuid 是列表

有些子服是「**一个端口挂好几个面板实例**」的用法 —— 比如 killer 换游戏，就是停掉当前实例、
启动另一个，它们共用同一个端口，**但同时至多只会有一个在跑**。这类情况下没法在配置里写死
"停哪一个"，所以插件的做法是：

每次真要关机时，把列表里的 uuid **逐个查一遍状态**（`GET /api/instance`），
**只对明确返回「运行中(3)」的实例发停止指令**。查不出来的、已经停的、正在启停的，一律不碰。

- 单个实例的子服照常写，推荐统一写成列表形式 `uuid = ["一串"]`
- 偷懒写 `uuid = "一串"` 也认，等价于一元素列表
- 列表里重复同一个 uuid 会自动去重，顺序保持配置里的顺序
- 面板上已经删掉的旧实例留在列表里无害：会被跳过，第一次查到时后台提醒一次「建议删掉」

服名写 `velocity.toml` 里 `[servers]` 下的那个名字，大小写不敏感；

| | `mode` | servers 空着算什么 | 什么时候用 |
| --- | --- | --- | --- |
| 黑名单 | `blacklist` | **盯所有子服**（危险：会连大厅一起盯上） | 只想排除少数几个服 |
| 白名单 | `whitelist` | **谁都不盯**（安全） | 只想关某几个服 |

服名写 `velocity.toml` 里 `[servers]` 下的那个名字，大小写不敏感；
`[servers.xxx]` 的 `xxx` 保持一致，否则插件不知道该停面板上哪个实例 → 只会在日志里提醒。

### 环境变量

`${env:名字}` 会被换成系统环境变量的值，`${env:名字:-兜底}` 没设置时用兜底值。
**改环境变量要重启代理**（`reload` 不会重读环境）。取不到且没写兜底 → 后台告警，该项按空处理。

### 密钥写法二：`ENC1:` 密文

不方便设环境变量时，先把明文 api-key 加密成 `ENC1:` 开头的串再填进 `[mcsm] apikey`：

```toml
[mcsm]
apikey = "ENC1:q2mK8v…（一长串 base64）"
```

- 加密页：<https://shijiu-world.github.io/VAutoStop/>（纯浏览器本地算，不联网、不上传）
- 算法：AES-256-GCM，密钥 = SHA-256(内置种子)，输出 = `ENC1:` + base64(IV + 密文 + 校验标签)
- 每次加密结果都不一样（随机 IV），哪个都能用
- **只有写在文件里的字面量才解密**：带 `ENC1:` 前缀才解，没前缀的明文原样用；
  `${env:XXX}` 取回来的值**不解密**
- 解不开 → 按空处理 + 打一条 warn，不会拿错的串去怼面板
- 定位：防「配置文件被拷走 / 外传 / 误传仓库」，**不是防逆向**（种子编在插件里，
  拿到 jar 的人理论上有能力还原）

---

## 三、命令

全部需要 `vautostop.admin` 权限（控制台自带）。主命令 `/vautostop`，别名 `/vas`。

| 命令 | 作用 |
| --- | --- |
| `/vautostop` / `/vautostop list` | 看在盯哪些服、每服现在几个人、连续空了几次、上次动作是什么 |
| `/vautostop check` | 立刻跑一轮巡检（跳过宽限期/冷却，但**连续次数照算**） |
| `/vautostop test` | 逐个 uuid 查一次面板实例状态 —— 验证 url / uuid / 密钥对不对，顺便看哪个实例在跑 |
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
   🔴 某个子服配了 **2 个以上 uuid** 时，这条**强制按 true 处理** —— 同端口互斥的那一组，
   不查就不知道该停哪个。
3. **uuid 查不到 = 只跳过它一个** —— 面板上已经删除、或者密钥没权限看的实例，
   面板会回 HTTP 200 但 `data` 是空的。插件把它当作「状态不明」，**绝不当成在跑**，
   只是从候选里剔除（第一次遇到会在后台提醒一次）；列表里其它 uuid 照常处理。
4. **发请求前再数一遍人数** —— 判空到发请求之间隔着一次 HTTP，这段时间可能有人进来；
   第二次读到非 0 就立即放弃这一轮。🔴 万一一时**没数出来**（主线程调度超时、代理正在关停），
   结论同样是「不敢关」——人数不明时不动手，是关服插件该有的样子。
5. **uuid 不像 32 位十六进制 → 跳过** —— 配置还是占位值时永远不会真的关掉任何东西。
6. **启动宽限期** —— 代理刚起来/reload 后的前 N 分钟不动手（各后端可能还在拉起来）。
7. **重试冷却** —— 同一服两次停机尝试至少隔 N 分钟，面板抽风时不会每轮都重试。
8. ** `[servers.x] enabled = false`** —— 配着但不用。
9. **dry-run** —— 走完所有判断、日志里写清楚"本会调用面板停机"，但不真的发请求。
10. **读失败保留旧配置** —— config.toml 写挂了继续用上一次那份，不会变成半成品。

### 日志：默认只在真的停机时才出

**默认 `verbose = false`**：控制台只在「真的把服关了」时打一条，`dry-run` 演习也算。
其余每轮必然发生的判断（还没到连续次数、在冷却中、这个实例没在跑、有人所以不关……）
一律走 debug，不打扰控制台 —— 间隔设成 1~2 分钟时它们能把日志刷满。

平时想看插件在想什么，用 **`/vautostop list`**：每个服现在几个人、连续空了几次、
上次结论是什么，都在里面。真要排查再开 `[log] verbose = true`（`/vautostop reload` 生效）。

⚠ **出错不受这条限制**：面板连不上、返回非 200 这类问题，同一个错误**第一次**一定打 WARN
并且带上响应原文，之后才降级为 debug，免得面板真挂了反而没人知道。

开 `verbose` 后能看到的全过程（默认只有最后两行会出现在控制台）：

```
[vautostop] 受监控子服：killer, bedwars, minigame, starvalley
[vautostop] killer 空了第 1/2 次，还没到连续次数，继续观察
[vautostop] killer 的实例 aaaaaaaa 是「停止」，不停它
[vautostop] killer 的实例 bbbbbbbb 正在运行 → 就停它
[vautostop] killer 的实例 cccccccc 状态不明（面板业务码 200，没有实例数据（uuid 不存在或无权限）），不停它
[vautostop] killer 的实例 cccccccc 查不到（同上）—— 这个 uuid 建议从配置里删掉
[vautostop] 正要停 killer 时它又有 2 人了，取消这次关机
[vautostop] 正要停 killer 时没能从主线程数出人数（调度超时或代理正在关停），这一轮先不动手
[vautostop] ⚠ killer 本该同时至多一个实例在跑，现在却有 2 个都在运行（aaaaaaaa, bbbbbbbb）
[vautostop] killer 的实例 bbbbbbbb 已请求面板停机（HTTP 200，面板状态码 停止中）   ← 默认就打这条
[vautostop] [dry-run] killer 已满足停机条件，本会调用面板停掉 1 个实例：bbbbbbbb   ← 默认也打这条
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

离线自测 56 项（不需要 Velocity，uuid 列表、面板响应的各种形态都在里面）：

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
