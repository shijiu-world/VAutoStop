package cn.shijiu.vautostop;

import cn.shijiu.vautostop.VasConfig.Target;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /vautostop <子命令>} —— 全插件就这一个命令入口（别名 {@code /vas}）。
 *
 * <p>子命令：
 * <pre>
 *   /vautostop              等同于 /vautostop list
 *   /vautostop list         看在盯哪些服、每服现在几个人、上次动作是什么
 *   /vautostop check        立刻跑一轮巡检（只跳过"启动宽限期"和"重试冷却"，
 *                           连续几次 0 人这条<b>照算</b>，所以空服要连敲两次才会真动手）
 *   /vautostop test         逐个子服查一次面板实例状态，验证 url/uuid/密钥对不对
 *   /vautostop reload       重读 config.toml 并按新间隔重新排期
 *   /vautostop version      版本号
 * </pre>
 * 权限 {@code vautostop.admin}，控制台自带。
 */
public final class AdminCommands implements SimpleCommand {

    private final VAutoStop plugin;

    public AdminCommands(VAutoStop plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (!Permissions.has(source)) {
            plugin.send(source, config().msg("no-permission"));
            return;
        }
        if (args.length == 0) {
            list(source, config());
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list":
            case "l":
            case "status":
                list(source, config());
                break;
            case "check":
            case "run":
                plugin.monitor().scheduleTick(true);
                plugin.send(source, config().msg("check-done",
                        "checks", String.valueOf(config().requiredEmptyChecks)));
                break;
            case "test":
                test(source, config());
                break;
            case "reload":
                plugin.reload(source);
                break;
            case "version":
                plugin.send(source, config().msg("version", "version", BuildConstants.VERSION));
                break;
            default:
                if (args.length == 1 && (args[0].equalsIgnoreCase("help") || args[0].equals("?"))) {
                    plugin.send(source, config().msg("usage"));
                    break;
                }
                plugin.send(source, config().msg("usage"));
                break;
        }
    }

    @Override
    public List<String> suggest(final Invocation invocation) {
        String[] args = invocation.arguments();
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            for (String name : new String[]{"list", "check", "test", "reload", "version"}) {
                if (name.startsWith(prefix)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return Permissions.has(invocation.source());
    }

    private VasConfig config() {
        return plugin.config();
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    private void list(CommandSource source, VasConfig c) {
        IdleMonitor monitor = plugin.monitor();
        plugin.send(source, c.msg("list-header"));
        String mode = c.mode() == VasConfig.FilterMode.WHITELIST ? "&a白名单" : "&6黑名单";
        String servers = c.filterServers.isEmpty() ? "（空）" : "：" + String.join(", ", c.filterServers);
        plugin.send(source, c.msg("list-summary",
                "state", c.enabled ? "&a开" : "&c关",
                "interval", String.valueOf(c.intervalMinutes),
                "checks", String.valueOf(c.requiredEmptyChecks),
                "mode", mode,
                "servers", servers));

        long elapsedMin = (System.nanoTime() - monitor.lastTickNanos()) / 60_000_000_000L;
        long next = Math.max(0L, c.intervalMinutes - elapsedMin);
        plugin.send(source, c.msg("list-next", "next", String.valueOf(next)));

        List<String> watched = monitor.watched(c);
        if (watched.isEmpty()) {
            plugin.send(source, c.msg("list-none"));
            return;
        }
        Map<String, IdleMonitor.State> states = monitor.states();
        for (String name : watched) {
            RegisteredServer rs = plugin.proxy().getServer(name).orElse(null);
            String players;
            if (rs == null) {
                players = "&c代理里没注册";
            } else {
                players = String.valueOf(rs.getPlayersConnected().size());
            }
            IdleMonitor.State st = states.get(name);
            Target t = c.target(name);
            String uuid;
            if (t == null) {
                uuid = "&c没配 [servers." + name + "]";
            } else {
                List<String> valid = t.validUuids();
                String daemon = (t.daemonId != null && !t.daemonId.isEmpty()) ? " &8(独立守护)" : "";
                if (valid.isEmpty()) {
                    uuid = "&c" + (t.uuids.isEmpty() ? "没配 uuid"
                            : t.uuids.size() + " 个 uuid 都不合法");
                } else if (valid.size() == 1) {
                    uuid = "&7" + valid.get(0) + daemon;
                } else {
                    uuid = "&7" + valid.size() + " 个实例&8(" + shortJoin(valid) + ")" + daemon;
                }
            }
            String note = st == null ? "还没巡检过" : st.lastResult;
            plugin.send(source, c.msg("list-line",
                    "server", name,
                    "players", players,
                    "empty", String.valueOf(st == null ? 0 : st.emptyChecks.get()),
                    "checks", String.valueOf(c.requiredEmptyChecks),
                    "uuid", uuid,
                    "note", note));
        }
    }

    // ------------------------------------------------------------------
    // test
    // ------------------------------------------------------------------

    /** 一次查询的结果：给玩家看的一行 + 它算不算「运行中」（null = 没能读出状态） */
    private static final class ProbeLine {
        final String text;
        final Boolean running;

        ProbeLine(String text, Boolean running) {
            this.text = text;
            this.running = running;
        }
    }

    /**
     * 向面板逐个 uuid 查一次实例状态：能查到 = 地址/uuid/密钥都对。
     * 配了多个实例的子服会每个 uuid 都查一遍，顺带告诉服主「现在到底哪个在跑」。
     */
    private void test(CommandSource source, VasConfig c) {
        if (!c.mcsm.enabled) {
            plugin.send(source, "&c[mcsm] enabled = false，插件现在不联网，测不了");
            return;
        }
        if (c.mcsm.url.isEmpty() || c.mcsm.daemonId.isEmpty()) {
            plugin.send(source, "&c[mcsm] 的 url 或 daemon-id 没填全，没法请求面板");
            return;
        }
        McsmApi api = new McsmApi(c);
        List<String> watched = plugin.monitor().watched(c);
        List<String> skipped = new ArrayList<>();
        // 子服名 → 它那几个 uuid 各自的查询结果
        Map<String, List<CompletableFuture<ProbeLine>>> byServer = new LinkedHashMap<>();
        int queries = 0;

        for (String name : watched) {
            Target t = c.target(name);
            if (t == null) {
                skipped.add("&8- &f" + name + " &c没配 [servers." + name + "]，不知道实例 uuid");
                continue;
            }
            List<String> valid = t.validUuids();
            if (valid.isEmpty()) {
                skipped.add("&8- &f" + name + " &c没有可用的 uuid（"
                        + (t.uuids.isEmpty() ? "没配" : "不像 32 位十六进制") + "）");
                continue;
            }
            String daemon = api.daemonOf(t);
            boolean multi = valid.size() > 1;
            List<CompletableFuture<ProbeLine>> lines = new ArrayList<>();
            for (String uuid : valid) {
                String shortId = McsmApi.shortUuid(uuid);
                String label = multi ? "&8[" + shortId + "] &r" : "";
                String url = api.detailUrl(uuid, daemon);
                String safeUrl = api.mask(url);
                queries++;
                lines.add(plugin.http().request(url, c.mcsm.timeoutMs, api.authHeaders()).handle((r, ex) -> {
                    if (ex != null) {
                        return new ProbeLine("&8- " + label + "&f" + name
                                + " &c请求异常：&f" + ex.getMessage(), null);
                    }
                    if (!r.success2xx()) {
                        // ⚠ URL 里带 &（?uuid=..&daemonId=..），直接发给玩家会被当颜色码吃掉 → 只进后台日志
                        plugin.logger().warn("[vautostop] 查 " + name + " 的实例 " + shortId
                                + " 失败：HTTP " + r.code + " " + safeUrl);
                        return new ProbeLine("&8- " + label + "&f" + name + " &7HTTP &6" + r.code
                                + " &7（完整地址见后台日志，apikey 已打码）", null);
                    }
                    McsmApi.Probe p = McsmApi.probe(r.body);
                    if (p.status == null) {
                        // uuid 不存在 / 没权限：面板照样回 200，但 data 是空的
                        return new ProbeLine("&8- " + label + "&f" + name
                                + " &6查不到这个实例 &8(" + p.reason + ")", null);
                    }
                    String tail = p.running() ? " &a← 就是这个在跑" : "";
                    return new ProbeLine("&8- " + label + "&f" + name + " &7HTTP &a" + r.code
                            + " &7｜实例 &e" + McsmApi.statusText(p.status) + " &8(" + p.status + ")" + tail,
                            p.running());
                }));
            }
            byServer.put(name, lines);
        }

        int count = queries;
        List<String> intro = skipped;
        List<CompletableFuture<?>> flat = new ArrayList<>();
        for (List<CompletableFuture<ProbeLine>> ls : byServer.values()) {
            flat.addAll(ls);
        }
        // 🔴 千万别在主线程 join()。等全部请求完成后再跳回主线程统一输出。
        CompletableFuture<Void> all = CompletableFuture.allOf(flat.toArray(new CompletableFuture<?>[0]));
        all.whenComplete((ignored, ex) -> plugin.runOnMain(() -> {
            plugin.send(source, "&7面板地址：&f" + c.mcsm.url + " &7｜ 守护：&f" + c.mcsm.daemonId);
            plugin.send(source, c.msg("test-start", "count", String.valueOf(count)));
            for (String line : intro) {
                plugin.send(source, line);
            }
            if (count == 0) {
                plugin.send(source, c.msg("test-none"));
                return;
            }
            for (Map.Entry<String, List<CompletableFuture<ProbeLine>>> e : byServer.entrySet()) {
                int running = 0;
                for (CompletableFuture<ProbeLine> f : e.getValue()) {
                    ProbeLine pl;
                    try {
                        pl = f.getNow(null);
                    } catch (RuntimeException oops) {
                        pl = null;
                    }
                    if (pl == null) {
                        plugin.send(source, "&8- &f" + e.getKey() + " &c无结果");
                        continue;
                    }
                    plugin.send(source, pl.text);
                    if (Boolean.TRUE.equals(pl.running)) {
                        running++;
                    }
                }
                if (e.getValue().size() > 1) {
                    plugin.send(source, "&8- &f" + e.getKey() + " &7合计 &f" + e.getValue().size()
                            + " &7个实例，&a" + running + " &7个在运行"
                            + (running == 1 ? "（空了就停它）" : ""));
                }
            }
        }));
    }

    /** 32 位 uuid 太长，只显示前 8 位就够认了 */
    private static String shortJoin(List<String> uuids) {
        List<String> out = new ArrayList<>();
        for (String u : uuids) {
            out.add(McsmApi.shortUuid(u));
        }
        return String.join(", ", out);
    }
}
