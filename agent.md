# VAutoStop · agent.md（给以后干活用）

> Velocity 端：定期巡检子服人数，**连续两次**读到 0 人 → 调 MCSManager 面板停掉那个实例。
> 本地工程 `D:\Code\mc\plugins\VAutoStop`，README 是面向服主的说明书，本文件是开发备忘。

## 一、它是干嘛的

- 只装代理端，子服不用装任何东西：人数来自 `RegisteredServer#getPlayersConnected()`，
  停机是让面板执行的 `GET /api/protected_instance/stop`。
- 配置 `plugins/vautostop/config.toml`（TOML，自研零依赖解析）。
- 命令 `/vautostop`（别名 `/vas`）：`list / check / test / reload / version`，权限 `vautostop.admin`。
- 首次启动会在 `plugins/vautostop/` 生成默认模板；**已存在的配置绝不被覆盖**。

## 二、改动前必须知道的三条铁律

1. 🔴 **连续两次才动手**：一次采样不能证明没人（玩家可能正在换服，那瞬间两服都是 0 人）。
   `required-empty-checks` 的钳位下限是 **2**（`VasConfig.load` 里 `clamp(…, 2, 1440)`），
   `/vautostop check` 也不得跳过这条 —— 只累加计数器。
   想放宽 = 提高次数，不许改成"一次就关"。
1.5. 🔴 **uuid 是列表，停机前必须逐个查**：服主有些子服「一个端口挂多个面板实例」
   （典型如 killer 换游戏 = 换实例），同时至多一个在跑。所以不能认定某一个 uuid，
   要遍历 `Target.validUuids()` 查 `/api/instance`，**只对明确返回「运行中(3)」的发 stop**。
   → 配了 2 个以上 uuid 时，`verify-running` **强制按 true**（`IdleMonitor#beginStop` 的 `probe` 变量），
   否则根本不知道该停谁。
2. 🟢 **这是"关服"插件，任何不确定的地方都往「不关」那侧倒**
   白名单空 = 谁都不盯；uuid 不是 32 位十六进制 = 跳过；面板查不到状态 = 跳过；
   配置读失败 = 保留旧配置继续跑。加新功能时别破坏这个倾向。
3. 🔴 **主线程只干事、不等事**：人数只在主线程读（统一走 `VAutoStop#runOnMain`），
   HTTP 全在 `HttpService` 自己的线程池上；往回顶主线程再给玩家发消息。
   千万别在主线程 `join()` 任何 HTTP future（`/vautostop test` 踩过：改成 `allOf(...).whenComplete(...)`）。
   ⚠ 停机链路是在 **HTTP 回调线程**里继续往下走的，所以那里**不许**直接调
   `rs.getPlayersConnected()` 兜底（曾经这么干过）—— 数不出来就返回 -1，按「人数不明」处理。
4. 🔴 **`System.nanoTime()` 的原点是任意的，别拿 0 当"很久以前"**：
   `State.lastStopAttemptNanos` 用 **负数表示「从没尝试过」**，判定收在 `VasConfig#inCooldown`。
   用 0 的话，机器刚开机那会儿 `now - 0` 会小于冷却时长 → 每个子服的**第一次停机都被误挡一轮**。

## 三、一次巡检的完整链路（`IdleMonitor#beginStop`）

```
① 取 Target.validUuids()（过滤掉占位/非法值；一个都没有就跳过）
→ ② 宽限期/连续次数/冷却
→ ③ 逐个 uuid 查 /api/instance（多个 uuid 时强制），只保留 status==3 的
→ ④ 回主线程再数一遍人数（非 0 就放弃；-1 = 没数出来，同样放弃）
→ ⑤ 对留在候选里的每个 uuid 发 /api/protected_instance/stop（或 dry-run 只记日志）
```
③ 由 `IdleMonitor#probeRunning` 负责（并行发请求，`allOf` 汇合），⑤ 是 `sendStops`。

每步失败都在 `State#lastResult` 里留原因，玩家用 `/vautostop list` 能看到。

## 四、面板对接要点

- 路径写死在 `McsmApi`：`stop = /api/protected_instance/stop`，`detail = /api/instance`。
- 鉴权照官方文档：apikey 拼 **URL 参数** + 必需头 `X-Requested-With: XMLHttpRequest`、
  `Content-Type: application/json; charset=utf-8`（缺头面板回的是 **403** 不是 401），加 `Authorization: Bearer` 冗余。
- ⚠ **任何打印 URL 的地方都要过 `McsmApi#mask()`**（apikey 在 URL 里）。
- ⚠ **URL 带 `&`，不能直接 `sendMessage` 给玩家**（会被当 `&` 颜色码吃掉）→ 只进后台日志。
- 状态码 `-1 忙碌 / 0 停止 / 1 停止中 / 2 启动中 / 3 运行中`；
  ⚠ 响应顶层的 `"status":200` 是 HTTP 层的码，**实例状态必须从 `data` 里取**。
- 🔴 **uuid 不存在时面板照样回 HTTP 200**，只是 `data` 是 `null`（或业务码非 200 也没 data）。
  所以必须在 `McsmApi#probe` 里把它和「实例停着 status=0」区分开：
  返回 `Probe{status=null, reason=…}`，上层按「状态不明」处理 = **跳过这个 uuid，绝不当成在跑**。
  这类多半是面板上删过的旧实例还留在配置里 → 用 `warnOnce` 提醒一次「建议从 uuid 列表里删掉」，之后闭嘴。
- 🔴 `HttpClient` 不要 `.executor(自己的池子)`（线程会互相饿死全超时）——`HttpService` 没设 executor 是对的。

## 五、构建 / 测试

```bash
# 构建（Git Bash，PowerShell 前置赋值会被丢弃）
JAVA_HOME="D:/Code/Java/zulu25.34.17-ca-jdk25.0.3-win_x64" mvn -B -o -DskipTests package
# ⚠ 离线必须带 -DskipTests：本机 .m2 缺 surefire-junit3 provider，联网才会自动下载。
#    别为此在 pom 里写死 skipTests —— CI 那边是联网的，不需要跳。

# 离线自测（不用 Velocity，72 项）
JDK="D:/Code/Java/zulu25.34.17-ca-jdk25.0.3-win_x64/bin"
"$JDK/javac" -encoding UTF-8 -cp target/classes -d target/test-classes tests/VasTest.java
"$JDK/java" -cp "target/classes;target/test-classes" cn.shijiu.vautostop.VasTest
# 输出是 GBK 乱码也无所谓，看最后一行 "通过 N 项，失败 0 项"
```

版本号只改 `pom.xml` 一处（`BuildConstants.VERSION` ← `templating-maven-plugin`）。
发流程与其它插件一致：改版本 → `git status` 干净 → 打 `v*` tag → Actions 挂 Release → 解包验
`velocity-plugin.json` 的 `version` 字段。

## 六、踩过的坑

- 🔴 **跨线程写的计数器不能用 `volatile long`**：主线程的 tick 在 `emptyChecks++`，
  停机回调线程同时可能在清零 —— `++` 不是原子操作，会丢一次计数。这两个字段现在是 `AtomicLong`。
- 📌 **`DEFAULT_MESSAGES` 里只放代码真正 `msg(key)` 过的条目**：曾经留了四条从来没
  接线的 key，服主在 `[messages]` 里改了毫无效果，只会白白怀疑插件坏了。
  同理 `intervalMillis()` 生产代码从不引用 —— 删掉，别留只有测试在用的僵尸 API。
- 📌 **自研 TOML 的数组元素也要走 `unescape`**：曾经只有单值那条路径处理转义，
  数组里写了转义序列就原样留着反斜杠，两边行为不一致。
- 📌 **「多个实例同时在跑」要出警告**：服主的前提是同一端口同时至多一个实例，
  真出现多个说明 uuid 列表可能配岔了。做法是 **照停 + 日志打警示**（不停反而是猜），
  `/vautostop test` 也会汇总告诉你是哪几个。
- 🔴 **在测试里验证「依赖真实系统状态」的行为必然失败**：`VasConfig#load` 读的是
  `System::getenv`，测试里塞的假 map 管不到。要验 env 优先级就调 `env(text, lookup, …)` 
  那个带自定义 lookup 的重载。

- 🔴 `RegisteredServer` 在 **`com.velocitypowered.api.proxy.server.RegisteredServer`**，
  不是 `com.velocitypowered.api.proxy.RegisteredServer`（第一次编译就栽在这儿）。
- ⚠ 只写 `@Plugin` 注解**不会**生成 `velocity-plugin.json`（本工程没开注解处理器）→
  `src/main/resources/velocity-plugin.json` 里写 `${project.version}`，靠 pom 的 resources filtering 填，别删。
- ⚠ `pom.xml` 里 config.toml **必须排除 filtering**（含 `${env:MCSM_KEY}`）。
- 📌 自研 TOML 的行尾注释要按引号状态扫，否则 `enabled = true # 说明` 会被翻成 false。
- 📌 极简 JSON 的数字字符集必须是完整 `+-0123456789.eE`；少一位就会把 `{"status":3}` 判成非法 JSON。
  ⚠ 顺带记：Git Bash 终端的显示/Grep 输出**渲染会乱序**（曾照它误判别的仓库缺数字字符），
  核对源码字面量要用 PowerShell 打字符码 `[int]$c`。
- 📌 一次性的告警（uuid 一个都不合法、子服没注册、某个 uuid 查不到）用 `warned` 去重，
  reload 时清空 —— 否则每 10 分钟刷一条。同一个 uuid 的告警 key 带短 uuid，互不覆盖。
- 🔴 **别手抄源码里的字面量去改源码**：本机 Git Bash / 终端对某些 ASCII 串的显示是乱序的
  （曾两度把 `0123456789abcdef…` 看成一串别的），Edit 的 old_string 因此对不上。
  遇到含 uuid/字符集的改动，用 **Python 按正则**改，改完打印 `ord()` 复核字符。
