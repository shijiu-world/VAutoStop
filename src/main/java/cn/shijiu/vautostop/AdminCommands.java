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
 *   /vautostop check        立刻跑一轮巡检（跳过"连续几次"和冷却，收到'<几个'就动手）
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
            } else if (!McsmApi.looksLikeUuid(t.uuid)) {
                uuid = "&cuuid 不合法";
            } else if (t.daemonId != null && !t.daemonId.isEmpty()) {
                uuid = "&7" + t.uuid + " &8(独立守护)";
            } else {
                uuid = "&7" + t.uuid;
            }
            String note = st == null ? "还没巡检过" : st.lastResult;
            plugin.send(source, c.msg("list-line",
                    "server", name,
                    "players", players,
                    "empty", String.valueOf(st == null ? 0 : st.emptyChecks),
                    "checks", String.valueOf(c.requiredEmptyChecks),
                    "uuid", uuid,
                    "note", note));
        }
    }

    // ------------------------------------------------------------------
    // test
    // ------------------------------------------------------------------

    /** 向面板查一次每个受监控子服的实例状态：能查到 = 地址/uuid/密钥都对 */
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
        Map<String, CompletableFuture<String>> pending = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();

        for (String name : watched) {
            Target t = c.target(name);
            if (t == null) {
                skipped.add("&8- &f" + name + " &c没配 [servers." + name + "]，不知道实例 uuid");
                continue;
            }
            if (!McsmApi.looksLikeUuid(t.uuid)) {
                skipped.add("&8- &f" + name + " &cuuid 不像 32 位十六进制：&f" + t.uuid);
                continue;
            }
            String url = api.detailUrl(t);
            String safeUrl = api.mask(url);
            pending.put(name, plugin.http().request(url, c.mcsm.timeoutMs, api.authHeaders()).handle((r, ex) -> {
                if (ex != null) {
                    return "&8- &f" + name + " &c请求异常：&f" + ex.getMessage();
                }
                if (!r.success2xx()) {
                    // ⚠ URL 里带 &（?uuid=..&daemonId=..），不能直接发给玩家：
                    //   会被当 & 颜色码吃掉 → 只能进后台日志
                    plugin.logger().warn("[vautostop] 查 " + name + " 的实例状态失败：HTTP "
                            + r.code + " " + safeUrl);
                    return "&8- &f" + name + " &7HTTP &6" + r.code
                            + " &7（完整地址见后台日志，apikey 已打码）";
                }
                Integer status = api.parseStatus(r.body);
                if (status == null) {
                    return "&8- &f" + name + " &c响应里读不到实例状态码：&7" + abbreviate(r.body);
                }
                return "&8- &f" + name + " &7HTTP &a" + r.code + " &7｜实例 &e"
                        + McsmApi.statusText(status) + " &8(" + status + ")";
            }));
        }

        int count = pending.size();
        List<String> intro = new ArrayList<>(skipped);
        // 🔴 千万别在主线程 join()。等全部请求完成后再跳回主线程统一输出。
        CompletableFuture<Void> all = CompletableFuture.allOf(
                pending.values().toArray(new CompletableFuture<?>[0]));
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
            for (Map.Entry<String, CompletableFuture<String>> e : pending.entrySet()) {
                String line;
                try {
                    line = e.getValue().getNow(null);
                } catch (RuntimeException oops) {
                    line = "&8- &f" + e.getKey() + " &c出错：" + oops.getMessage();
                }
                plugin.send(source, line == null ? "&8- &f" + e.getKey() + " &c无结果" : line);
            }
        }));
    }

    private static String abbreviate(String body) {
        String s = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
