package cn.shijiu.vautostop;

import cn.shijiu.vautostop.VasConfig.Target;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 巡检核心：到点跑一遍各子服的人数，满足条件就把停机请求发出去。
 *
 * <p>几个刻意的取舍：
 * <ol>
 *   <li><b>人数只在主线程读</b>：{@code getPlayersConnected()} 属于 Velocity 内部会话状态，
 *       不停服的前提下跨线程读虽不一定炸，但没有必要冒险，全部包到 {@link #scheduleTick}
 *       里由代理的主线程执行。</li>
 *   <li><b>停机前再数一遍人数</b>：从「判定为空」到「请求发出」之间隔着一次 HTTP，
 *       这段时间完全可能有玩家进来；第二次读到非 0 就直接放弃这一轮。</li>
 *   <li><b>同一时刻同一个服只跑一条停机链路</b>：靠 {@link State#busy} 标记，
 *       防止每次巡检叠加请求（尤其 verify-running 那段本来就慢）。</li>
 *   <li><b>告警去重</b>：uuid 没配/不合法、子服不存在这类问题只报一次，
 *       否则每 10 分钟一条，日志会被自己的提醒刷屏。</li>
 *   <li><b>日志分级</b>：控制台<b>只在真的把服关了</b>（含 dry-run 演习）时才出 INFO；
 *       「还没到连续次数」「在冷却中」「这个实例没在跑」这些每轮必然发生的判断走
 *       {@link #detail(String)}（默认 debug）。服主想要细节就开 {@code [log] verbose}，
 *       平时看 {@code /vautostop list} 的「上次结论」。</li>
 * </ol>
 */
public final class IdleMonitor {

    /** 某个子服的巡检状态（跨线程可见：字段 volatile，会被多线程写的计数器用 AtomicLong） */
    public static final class State {
        /**
         * 连续查到 0 人的次数（有人就清零）。
         *
         * <p>用 AtomicLong 而不是 volatile long：主线程的 tick() 在 {@code ++}，
         * 而停机回调线程可能跨线程把它清零 —— {@code volatile long} 的 ++ 不是原子操作，
         * 两边恰好撞上会丢一次计数。
         */
        final AtomicLong emptyChecks = new AtomicLong();
        /**
         * 最后一次尝试停机的单调时钟；<b>负数 = 从没尝试过</b>。
         *
         * <p>🔴 不能留 0 当"很久以前"：{@code System.nanoTime()} 的原点是<b>任意的</b>，
         * 有些 JVM/系统上它就是 JVM 启动时刻（机器刚开机时只有几十秒）。那样一来
         * {@code now - 0} 小于冷却时间，会被误判成"刚停过、还在冷却"，
         * 把每个子服的第一次停机都白白推迟一轮。没尝试过就是没尝试过，用负数显式表达。
         */
        volatile long lastStopAttemptNanos = -1;
        /** 最后一次被巡检到的时间 */
        volatile long lastCheckNanos;
        /** 最后一次读到的人数，-1 = 还没查过 */
        volatile int players = -1;
        /** 累计成功发出的停机请求数（跨线程写，所以用 AtomicLong） */
        final AtomicLong stopCount = new AtomicLong();
        /** 最近一次动作的结论（给 /vautostop list 看） */
        volatile String lastResult = "待巡检";
        /** 停机链路是否正在跑（http 往返期间别再来一条） */
        volatile boolean busy;
    }

    private final VAutoStop plugin;
    private final Logger logger;
    private final HttpService http;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private volatile ScheduledTask task;
    private volatile VasConfig config;
    private volatile long startedNanos;
    private volatile long lastTickNanos;

    public IdleMonitor(VAutoStop plugin, Logger logger, HttpService http) {
        this.plugin = plugin;
        this.logger = logger;
        this.http = http;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** （重新）排期：每次 reload 都会走到这里，旧任务先取消 */
    public synchronized void restart(VasConfig c) {
        cancelTask();
        warned.clear();
        this.config = c;
        this.startedNanos = System.nanoTime();
        this.lastTickNanos = startedNanos;
        if (c == null || !c.enabled) {
            return;
        }
        long interval = c.intervalMinutes;
        this.task = plugin.proxy().getScheduler().buildTask(plugin, () -> tick(false))
                .delay(interval, TimeUnit.MINUTES)
                .repeat(interval, TimeUnit.MINUTES)
                .schedule();
    }

    /** 代理关停：停掉定时任务，别在关服过程中还在往面板发请求 */
    public synchronized void shutdown() {
        cancelTask();
    }

    private void cancelTask() {
        ScheduledTask t = task;
        task = null;
        if (t != null) {
            try {
                t.cancel();
            } catch (Exception ignored) {
                // 取消失败无所谓，任务本就要随插件卸载消亡
            }
        }
    }

    /**
     * 手动触发一次巡检（{@code /vautostop check}）。
     *
     * <p>force 只等于「跳过启动宽限期和重试冷却」，<b>不等于跳过连续次数</b>：
     * 连续 {@code required-empty-checks} 次读到 0 人才动手是硬性条件，
     * 手动巡检也一样只累加计数器 —— 想测出一次真正的关机，连着敲两次 check 即可。
     */
    public void scheduleTick(boolean force) {
        plugin.runOnMain(() -> tick(force));
    }

    // ------------------------------------------------------------------
    // 查询（给命令用）
    // ------------------------------------------------------------------

    public Map<String, State> states() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(states));
    }

    public long lastTickNanos() {
        return lastTickNanos;
    }

    public long startedNanos() {
        return startedNanos;
    }

    /** 满足过滤条件、且真的在被盯的子服名字（按配置里/代理里的真实名字） */
    public List<String> watched(VasConfig c) {
        Map<String, RegisteredServer> byLower = allServers();
        Set<String> names = new LinkedHashSet<>();
        for (Target t : c.targets.values()) {
            if (t.enabled) {
                RegisteredServer rs = byLower.get(t.name.toLowerCase(Locale.ROOT));
                names.add(rs == null ? t.name : rs.getServerInfo().getName());
            }
        }
        for (String key : byLower.keySet()) {
            if (c.selected(key)) {
                names.add(byLower.get(key).getServerInfo().getName());
            }
        }
        // 配置里显式 enabled=false 的，即使被黑名单选中也不盯
        List<String> out = new ArrayList<>();
        for (String name : names) {
            Target t = c.target(name);
            if (t != null && !t.enabled) {
                continue;
            }
            out.add(name);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 一轮巡检
    // ------------------------------------------------------------------

    private void tick(boolean force) {
        lastTickNanos = System.nanoTime();
        VasConfig c = config;
        if (c == null || !c.enabled) {
            return;
        }
        long now = lastTickNanos;
        if (!force && now - startedNanos < c.graceMillis() * 1_000_000L) {
            // 启动宽限期内只记不算账：刚起代理时各后端可能还在拉起来
            return;
        }

        Map<String, RegisteredServer> byLower = allServers();
        for (String display : watched(c)) {
            RegisteredServer rs = byLower.get(display.toLowerCase(Locale.ROOT));
            if (rs == null) {
                warnOnce("missing-server:" + display,
                        "配置盯的子服 " + display + " 在 velocity.toml 的 [servers] 里并不存在，跳过");
                continue;
            }
            int players = rs.getPlayersConnected().size();
            State st = states.computeIfAbsent(display, k -> new State());
            st.players = players;
            st.lastCheckNanos = now;

            if (players > 0) {
                long had = st.emptyChecks.get();
                if (had > 0) {
                    detail(display + " 又有人了（" + players + " 人），空服计数清零");
                }
                st.emptyChecks.set(0);
                st.lastResult = "有人，不关";
                continue;
            }

            long checks = st.emptyChecks.incrementAndGet();
            // 🔴 连续次数是铁律，/vautostop check 也不得跳过 —— 一次采样说明不了"真没人"
            if (checks < c.requiredEmptyChecks) {
                detail(display + " 空了第 " + checks + "/"
                        + c.requiredEmptyChecks + " 次，还没到连续次数，继续观察");
                st.lastResult = "空 " + checks + "/" + c.requiredEmptyChecks + " 次";
                continue;
            }
            if (st.busy) {
                detail(display + " 上一轮停机流程还没走完，这一轮跳过");
                continue;
            }
            // 没尝试过（负数）不算冷却 —— 详见 VasConfig#inCooldown 上那段关于 nanoTime 原点的说明
            if (!force && VasConfig.inCooldown(st.lastStopAttemptNanos, now, c.cooldownMillis())) {
                long waitedMin = (now - st.lastStopAttemptNanos) / 60_000_000_000L;
                detail(display + " 距上次停机尝试才 " + waitedMin + " 分钟"
                        + "（冷却 " + c.retryCooldownMinutes + " 分钟），这一轮跳过");
                continue;
            }
            st.lastStopAttemptNanos = now;
            beginStop(c, rs, st);
        }
    }

    /**
     * 走完一条「真的要把服关掉」的链路：查每个候选实例的状态 → 再数一遍人数 → 给在跑的实例发停机。
     * 每一步失败都停下来，并在 {@link State#lastResult} 里留下原因。
     *
     * <p>uuid 现在是<b>列表</b>（同一端口可能挂着多个实例，同时至多跑一个），所以停机前必须
     * 逐个查一遍面板，只对明确处于「运行中(3)」的实例发停止 —— 查不出来的、已经停的、正在启停的，
     * 一律不碰。
     */
    private void beginStop(VasConfig c, RegisteredServer rs, State st) {
        String name = rs.getServerInfo().getName();
        Target t = c.target(name);
        if (t == null) {
            warnOnce("no-section:" + name,
                    name + " 空了，但没配 [servers." + name + "]，不知道该停面板上哪个实例，跳过");
            st.lastResult = "缺 [servers." + name + "]";
            return;
        }
        List<String> uuids = t.validUuids();
        if (uuids.isEmpty()) {
            warnOnce("bad-uuid:" + name,
                    name + " 没配可用的实例 uuid（应为 32 位十六进制），跳过"
                            + (t.uuids.isEmpty() ? "：[servers." + name + "] 里连 uuid 这行都没有"
                                                 : "（写了 " + t.uuids.size() + " 个，没一个像 uuid）"));
            st.lastResult = t.uuids.isEmpty() ? "没配 uuid" : "uuid 都不合法";
            return;
        }
        if (!c.mcsm.enabled) {
            detail(name + " 已空置满足停机条件，但 [mcsm] enabled=false（调试模式），不联网");
            st.lastResult = "面板对接关闭中";
            return;
        }
        if (c.mcsm.url.isEmpty() || c.mcsm.daemonId.isEmpty()) {
            warnOnce("bad-mcsm:", "[mcsm] 的 url 或 daemon-id 没填全，没法请求面板");
            st.lastResult = "面板地址未配置";
            return;
        }

        st.busy = true;
        McsmApi api = new McsmApi(c);
        long timeout = c.mcsm.timeoutMs;
        String daemon = api.daemonOf(t);
        boolean multi = uuids.size() > 1;
        // 🔴 配了多个实例，就必须先查状态才知道该停哪个 —— 这时候 verify-running 强制生效
        boolean probe = c.mcsm.verifyRunning || multi;
        if (multi && !c.mcsm.verifyRunning) {
            warnOnce("force-probe:" + name, name + " 配了 " + uuids.size()
                    + " 个实例（同一端口互斥），必须逐个查状态才知道该停哪个 —— 本服强制按 verify-running=true 处理");
        }

        CompletableFuture<List<String>> candidates = probe
                ? probeRunning(api, daemon, uuids, timeout, name, st)
                : CompletableFuture.completedFuture(uuids);

        CompletableFuture<Boolean> chain = candidates.thenCompose(running -> {
            if (running.isEmpty()) {
                detail(name + " 的 " + uuids.size() + " 个实例在面板上都不算「运行中」，不用停");
                // 面板上根本没实例在跑 = 这个子服压根不在线，人数恒为 0，
                // 「连续空」会一路涨出「58/2」这种数字。清零：等服务真上线再重新数。
                // 下方冷却照旧挡着，不会因为清零就每轮都去怼面板。
                st.emptyChecks.set(0);
                st.lastResult = "无运行中的实例";
                return CompletableFuture.completedFuture(false);
            }
            detail(name + " 面板上正在跑的实例 " + running.size() + "/" + uuids.size()
                    + " 个：" + shortJoin(running));
            if (running.size() > 1) {
                // 业务前提是「同一端口同时至多一个实例」，跑起来不止一个说明 uuid 列表可能配岔了
                warnThenDebug("multi-running:" + name, "⚠ " + name + " 本该同时至多一个实例在跑，现在却有 "
                        + running.size() + " 个都在运行（" + shortJoin(running) + "）。仍会逐个请求停机；"
                        + "如果这不是你想要的，核对一下 [servers." + name + "] 的 uuid 列表");
            }
            // ② 停机前再数一次人数：判空到发请求之间隔着 HTTP，这段时间可能有人进来
            return countAgain(rs).thenCompose(count -> {
                if (count < 0) {
                    detail("正要停 " + name + " 时没能从主线程数出人数"
                            + "（调度超时或代理正在关停），这一轮先不动手");
                    st.lastResult = "人数没数到，不敢关";
                    return CompletableFuture.completedFuture(false);
                }
                if (count > 0) {
                    detail("正要停 " + name + " 时它又有 " + count + " 人了，取消这次关机");
                    st.players = count;
                    st.emptyChecks.set(0);
                    st.lastResult = "关机前有人进来";
                    return CompletableFuture.completedFuture(false);
                }
                return sendStops(api, daemon, running, timeout, c.mcsm.dryRun, name, st);
            });
        });

        chain.whenComplete((ok, ex) -> {
            st.busy = false;
            if (ex != null) {
                warnThenDebug("chain:" + name, name + " 的停机流程意外中断：" + ex.getMessage());
                st.lastResult = "流程异常";
            }
        });
    }

    /**
     * 逐个查 uuid 列表里的实例状态，返回其中<b>明确处于「运行中(3)」</b>的那些。
     * 查不到的（uuid 不存在/没权限）、明确不是运行中的，都不进结果 —— 只停有把握的那个。
     */
    private CompletableFuture<List<String>> probeRunning(McsmApi api, String daemon, List<String> uuids,
                                                         long timeout, String name, State st) {
        List<String> running = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String uuid : uuids) {
            String shortId = McsmApi.shortUuid(uuid);
            String detailUrl = api.detailUrl(uuid, daemon);
            String safeDetail = api.mask(detailUrl);
            futures.add(http.request(detailUrl, timeout, api.authHeaders()).handle((r, ex) -> {
                if (ex != null) {
                    warnThenDebug("probe-err:" + name + ":" + shortId,
                            "查 " + name + " 的实例 " + shortId + " 出错：" + ex.getMessage());
                    st.lastResult = "查状态异常";
                    return null;
                }
                if (!r.success2xx()) {
                    warnThenDebug("probe-http:" + name + ":" + shortId + ":" + r.code,
                            "查 " + name + " 的实例 " + shortId + " 失败：HTTP " + r.code
                                    + " " + safeDetail + " 响应：" + trim(r.body));
                    st.lastResult = "查状态 HTTP " + r.code;
                    return null;
                }
                McsmApi.Probe p = McsmApi.probe(r.body);
                if (p.status == null) {
                    // 「实例不存在」是这里最常见的结论 —— 多半是面板上删过的旧实例还留在配置里
                    warnOnce("no-instance:" + name + ":" + shortId, name + " 的实例 "
                            + shortId + " 查不到（" + p.reason + "）。如果它已经被删了，"
                            + "建议把它从 [servers." + name + "] 的 uuid 列表里去掉");
                    detail(name + " 的实例 " + shortId + " 状态不明（" + p.reason + "），不停它");
                    st.lastResult = "实例 " + shortId + " 查不到";
                    return null;
                }
                if (p.running()) {
                    detail(name + " 的实例 " + shortId + " 正在运行 → 就停它");
                    running.add(uuid);
                } else {
                    detail(name + " 的实例 " + shortId + " 是「"
                            + McsmApi.statusText(p.status) + "」，不停它");
                }
                return null;
            }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                .thenApply(v -> new ArrayList<>(running));
    }

    /** ③ 给这批在跑的实例发停机请求（或 dry-run 只打日志） */
    private CompletableFuture<Boolean> sendStops(McsmApi api, String daemon, List<String> running,
                                                 long timeout, boolean dryRun, String name, State st) {
        if (dryRun) {
            logger.info("[dry-run] " + name + " 已满足停机条件，本会调用面板停掉 "
                    + running.size() + " 个实例：" + shortJoin(running)
                    + "（mcsm.dry-run=true，未真正发送）");
            st.lastResult = "dry-run，本会停 " + shortJoin(running);
            st.stopCount.addAndGet(running.size());
            return CompletableFuture.completedFuture(true);
        }
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (String uuid : running) {
            String shortId = McsmApi.shortUuid(uuid);
            String stopUrl = api.stopUrl(uuid, daemon);
            String safeStop = api.mask(stopUrl);
            futures.add(http.request(stopUrl, timeout, api.authHeaders()).handle((r, ex) -> {
                if (ex != null) {
                    warnThenDebug("stop-err:" + name + ":" + shortId,
                            "停 " + name + " 的实例 " + shortId + " 时出错：" + ex.getMessage());
                    st.lastResult = "停服请求异常";
                    return false;
                }
                if (!r.success2xx()) {
                    warnThenDebug("stop-http:" + name + ":" + shortId + ":" + r.code,
                            "停 " + name + " 的实例 " + shortId + " 失败：HTTP " + r.code
                                    + " " + safeStop + " 响应：" + trim(r.body));
                    st.lastResult = "停服 HTTP " + r.code;
                    return false;
                }
                Integer status = api.parseStatus(r.body);
                logger.info(name + " 的实例 " + shortId + " 已请求面板停机（HTTP " + r.code
                        + (status == null ? "" : "，面板状态码 " + McsmApi.statusText(status)) + "）");
                st.lastResult = "已请求停机（" + shortId + "）";
                st.stopCount.incrementAndGet();
                st.emptyChecks.set(0);
                return true;
            }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).thenApply(v -> {
            for (CompletableFuture<Boolean> f : futures) {
                if (Boolean.TRUE.equals(f.getNow(false))) {
                    return true;
                }
            }
            st.lastResult = "停服失败，看后台日志";
            return false;
        });
    }

    /** 把一串 32 位 uuid 缩成前 8 位拼起来，日志和命令里好读 */
    private static String shortJoin(List<String> uuids) {
        List<String> out = new ArrayList<>();
        for (String u : uuids) {
            out.add(McsmApi.shortUuid(u));
        }
        return String.join(", ", out);
    }

    /**
     * 过程性日志：默认走 debug，只有 {@code [log] verbose = true} 时才提到 INFO。
     *
     * <p>为什么要分级：间隔设成 1 分钟时，光「冷却中，这一轮跳过」这一条就会
     * 每分钟每个服刷一次，把真正有用的停机记录淹掉。规则是 ——
     * <b>INFO 只留给「真的把服关了」（含 dry-run 演习），过程判断统统 debug</b>。
     * 想看细节就开 {@code verbose}，或者用 {@code /vautostop list} 看每个服的「上次结论」。
     */
    private void detail(String message) {
        VasConfig c = config;
        if (c != null && c.verbose) {
            logger.info(message);
        } else {
            logger.debug(message);
        }
    }

    /**
     * 回到主线程再数一次人数。返回 -1 = <b>没数出来</b>（调度超时、代理正在关停都走这条）。
     *
     * <p>⚠ 刻意不在调用线程先读一次当兜底：调用方是 HTTP 回调线程，而 {@code getPlayersConnected()}
     * 属于 Velocity 内部会话状态，只能在主线程碰。拿不到就老实返回 -1，让调用方按
     * 「人数不明」处理 —— 这是关服插件，不确定就该往不关那边倒，而不是拿个数碰运气。
     */
    private CompletableFuture<Integer> countAgain(RegisteredServer rs) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        plugin.runOnMain(() -> {
            try {
                future.complete(rs.getPlayersConnected().size());
            } catch (RuntimeException e) {
                future.complete(-1);
            }
        });
        return future.orTimeout(5, TimeUnit.SECONDS).exceptionally(e -> -1);
    }

    private Map<String, RegisteredServer> allServers() {
        Map<String, RegisteredServer> byLower = new LinkedHashMap<>();
        for (RegisteredServer s : plugin.proxy().getAllServers()) {
            byLower.put(s.getServerInfo().getName().toLowerCase(Locale.ROOT), s);
        }
        return byLower;
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            logger.warn(message + "（这个提醒每个问题只报一次，改完配置 /vautostop reload 后会重新计数）");
        }
    }

    /**
     * 错误日志：同一个问题<b>第一次</b>打 WARN，之后降级到 debug。
     *
     * <p>面板地址/密钥写错时，如果每轮每个实例都打一条 WARN，几分钟就能把控制台刷满
     * —— 但这又确实是得让人知道的事，所以第一次一定报全（含响应原文），
     * 之后只在 {@code verbose} 下记，日常看 {@code /vautostop list} 的「上次结论」即可。
     * {@code /vautostop reload} 会清空去重集合，重新报一次。
     */
    private void warnThenDebug(String key, String message) {
        if (warned.add(key)) {
            logger.warn(message + "（同样的问题之后只记 debug，可用 /vautostop list 查看）");
        } else {
            detail(message);
        }
    }

    private static String trim(String body) {
        String s = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
