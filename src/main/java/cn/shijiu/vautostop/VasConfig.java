package cn.shijiu.vautostop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * config.toml 的内存快照。每次 {@code /vautostop reload} 重新解析一份。
 *
 * <p>铁律：
 * <ul>
 *   <li>所有取值都带默认值 —— 服主删一行不能让插件变半成品。</li>
 *   <li>这个是「关服」插件，过滤规则默认走<b>失败也安全</b>的一侧：白名单空 = 谁都不关。</li>
 * </ul>
 */
public final class VasConfig {

    /** 名单模式：白名单=只管列出的；黑名单=列出来的不管 */
    public enum FilterMode {
        WHITELIST,
        BLACKLIST
    }

    /**
     * 一个受监控的子服：名字（跟 velocity.toml 对齐）+ 它在面板上可能对应的实例。
     *
     * <p>为什么 uuid 是<b>列表</b>：服主有些子服共用同一个端口（比如 killer 换游戏靠换实例实现），
     * 面板上是好几个实例，但<b>同时至多只会有一个在跑</b>。所以要关的时候不能认定某一个，
     * 得把列表里的 uuid 逐个查一遍，谁在跑就停谁。
     */
    public static final class Target {
        public final String name;
        /** 面板实例 uuid 列表（按配置顺序去重后的结果） */
        public final List<String> uuids;
        public final String daemonId;
        public final boolean enabled;

        Target(String name, List<String> uuids, String daemonId, boolean enabled) {
            this.name = name;
            this.uuids = Collections.unmodifiableList(new ArrayList<>(uuids));
            this.daemonId = daemonId;
            this.enabled = enabled;
        }

        /** 列表里看着像面板实例 uuid（32 位十六进制）的那些；占位值/写错的一律滤掉 */
        public List<String> validUuids() {
            List<String> out = new ArrayList<>();
            for (String u : uuids) {
                if (McsmApi.looksLikeUuid(u)) {
                    out.add(u);
                }
            }
            return out;
        }
    }

    /** [mcsm]：面板在哪、怎么认证、要不要真的发请求 */
    public static final class Mcsm {
        public final boolean enabled;
        public final String url;
        public final String daemonId;
        public final String apikey;
        public final int timeoutMs;
        public final boolean verifyRunning;
        public final boolean dryRun;

        Mcsm(boolean enabled, String url, String daemonId, String apikey,
             int timeoutMs, boolean verifyRunning, boolean dryRun) {
            this.enabled = enabled;
            this.url = url;
            this.daemonId = daemonId;
            this.apikey = apikey;
            this.timeoutMs = timeoutMs;
            this.verifyRunning = verifyRunning;
            this.dryRun = dryRun;
        }
    }

    /** ${env:NAME} 或 ${env:NAME:-默认值} */
    private static final Pattern ENV_TOKEN = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\\}");

    /** 配置里缺某条文案时的兜底（服主删了 key 也不会 NPE） */
    private static final Map<String, String> DEFAULT_MESSAGES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("prefix", "&6[VAutoStop] &r");
        m.put("no-permission", "&c你没有权限使用这个命令（需要 vautostop.admin）");
        m.put("usage", "&7用法：&a/vautostop &7[list|check|test|reload|version]");
        m.put("reloaded", "&a配置已重载，巡检任务已按新的间隔重新排期");
        m.put("reload-failed", "&c配置读取失败，继续用旧配置：%reason%");
        m.put("disabled", "&7总闸是关的（config.toml 第一行 enabled = false），什么都不做");
        m.put("list-header", "&6========== VAutoStop ==========");
        m.put("list-summary", "&7总闸 %state%&7 ｜ 每 &e%interval% &7分钟查一次 ｜ 连续空 &e%checks% &7次才动手 ｜ %mode%&7%servers%");
        m.put("list-next", "&7下次巡检：约 &e%next% &7分钟后");
        m.put("list-none", "&7当前没有符合过滤条件的子服");
        m.put("list-line", "&8- &f%server% &7人数 &e%players% &7｜连续空 &e%empty%&8/%checks% &7｜%uuid%&7｜%note%");
        m.put("check-done", "&a已按当前人数跑了一轮巡检（同一个服要连续这样 %checks% 次没人才会真关机）");
        m.put("test-none", "&7没有可查的子服（都是没配 uuid 或面板关了的）");
        m.put("test-start", "&7正在向面板查询 %count% 个实例的状态…");
        m.put("test-ok", "&8- &f%server% &7HTTP &e%code% &7｜实例 &e%status%");
        m.put("test-fail", "&8- &f%server% &c请求失败 &7%error%");
        m.put("version", "&7VAutoStop &ev%version% &7（Velocity 端 · 拾玖世界）");
        DEFAULT_MESSAGES = Collections.unmodifiableMap(m);
    }

    public final boolean enabled;
    public final int intervalMinutes;
    public final int requiredEmptyChecks;
    public final FilterMode mode;
    public final List<String> filterServers;
    public final LinkedHashMap<String, Target> targets;
    public final Mcsm mcsm;
    public final int startupGraceMinutes;
    public final int retryCooldownMinutes;

    /** 配置里引用到的环境变量名（只存名字，不存值） */
    public final Set<String> envNames;

    /** 引用了但系统里没设置的环境变量名 */
    public final Set<String> envMissing;

    private final Map<String, String> messages;
    private final Map<String, Target> targetsByLower;

    private VasConfig(boolean enabled, int intervalMinutes, int requiredEmptyChecks,
                      FilterMode mode, List<String> filterServers, LinkedHashMap<String, Target> targets,
                      Mcsm mcsm, int startupGraceMinutes, int retryCooldownMinutes,
                      Map<String, String> messages, Set<String> envNames, Set<String> envMissing) {
        this.enabled = enabled;
        this.intervalMinutes = intervalMinutes;
        this.requiredEmptyChecks = requiredEmptyChecks;
        this.mode = mode;
        this.filterServers = Collections.unmodifiableList(filterServers);
        this.targets = targets;
        this.mcsm = mcsm;
        this.startupGraceMinutes = startupGraceMinutes;
        this.retryCooldownMinutes = retryCooldownMinutes;
        this.messages = messages;
        this.envNames = Collections.unmodifiableSet(envNames);
        this.envMissing = Collections.unmodifiableSet(envMissing);

        Map<String, Target> lower = new LinkedHashMap<>();
        for (Target t : targets.values()) {
            lower.put(t.name.toLowerCase(Locale.ROOT), t);
        }
        this.targetsByLower = Collections.unmodifiableMap(lower);
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    /** @param t Toml.parse() 出来的扁平表 */
    public static VasConfig load(Map<String, Object> t) {
        Set<String> envNames = new LinkedHashSet<>();
        Set<String> envMissing = new LinkedHashSet<>();

        List<String> filterServers = new ArrayList<>();
        for (String raw : Toml.list(t, "filter.servers")) {
            String v = env(raw.trim(), envNames, envMissing);
            if (!v.isEmpty()) {
                filterServers.add(v);
            }
        }
        FilterMode mode = FilterMode.WHITELIST.name().equalsIgnoreCase(
                Toml.string(t, "filter.mode", "blacklist").trim()) ? FilterMode.WHITELIST : FilterMode.BLACKLIST;

        // [servers.<名字>]：不管名单怎么配，配了就算一个目标（uuid 一个都不合法时后面会跳过）
        LinkedHashMap<String, Target> targets = new LinkedHashMap<>();
        for (String name : serverSectionNames(t)) {
            // 🔴 uuid 是「列表」：同一个端口可能挂着多个面板实例（同时至多跑一个）。
            //    写单个字符串也认（Toml.list 会把标量当一个元素的列表）。
            List<String> uuids = new ArrayList<>();
            for (String raw : Toml.list(t, "servers." + name + ".uuid")) {
                String v = env(raw.trim(), envNames, envMissing).trim();
                if (!v.isEmpty() && !uuids.contains(v)) {
                    uuids.add(v);
                }
            }
            String daemon = env(Toml.string(t, "servers." + name + ".daemon-id", ""), envNames, envMissing).trim();
            boolean on = Toml.bool(t, "servers." + name + ".enabled", true);
            targets.put(name, new Target(name, uuids, daemon, on));
        }

        Mcsm mcsm = new Mcsm(
                Toml.bool(t, "mcsm.enabled", true),
                env(Toml.string(t, "mcsm.url", ""), envNames, envMissing).trim(),
                env(Toml.string(t, "mcsm.daemon-id", ""), envNames, envMissing).trim(),
                env(Toml.string(t, "mcsm.apikey", ""), envNames, envMissing).trim(),
                Math.max(500, Toml.integer(t, "mcsm.timeout-ms", 8000)),
                Toml.bool(t, "mcsm.verify-running", true),
                Toml.bool(t, "mcsm.dry-run", false)
        );

        Map<String, String> messages = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : t.entrySet()) {
            if (e.getKey().startsWith("messages.") && e.getValue() != null) {
                messages.put(e.getKey().substring("messages.".length()), String.valueOf(e.getValue()));
            }
        }

        return new VasConfig(
                Toml.bool(t, "enabled", true),
                clamp(Toml.integer(t, "interval-minutes", 10), 1, 1440),
                // 🔴 至少两次：一次采样可能是"玩家正在换服/刚上线瞬间"，错关一个还在被使用的服代价太大
                clamp(Toml.integer(t, "required-empty-checks", 2), 2, 1440),
                mode, filterServers, targets, mcsm,
                clamp(Toml.integer(t, "safety.startup-grace-minutes", 3), 0, 1440),
                clamp(Toml.integer(t, "safety.retry-cooldown-minutes", 5), 0, 1440),
                messages, envNames, envMissing
        );
    }

    /** 从扁平表里挑出 [servers.xxx] 这种分节的名字（只看一层，xxx 里再带点的不管） */
    static List<String> serverSectionNames(Map<String, Object> t) {
        Set<String> names = new LinkedHashSet<>();
        for (String key : t.keySet()) {
            if (!key.startsWith("servers.")) {
                continue;
            }
            String rest = key.substring("servers.".length());
            int dot = rest.indexOf('.');
            String name = dot < 0 ? rest : rest.substring(0, dot);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    private static int clamp(int v, int min, int max) {
        return Math.min(max, Math.max(min, v));
    }

    /**
     * 把 ${env:NAME}（或 ${env:NAME:-默认值}）换成环境变量的值。
     *
     * <p>取不到且没写默认值 → 置空并记进 missing（插件加载完会打警告）。
     * 绝不保留 {@code ${env:xxx}} 字面量：密钥没配宁可发空，也不能把占位符原样发给面板。
     */
    private static String env(String text, Set<String> names, Set<String> missing) {
        return env(text, System::getenv, names, missing);
    }

    /** 同上，但取值来源可换（离线测试时不用真去设系统环境变量） */
    static String env(String text, Function<String, String> lookup, Set<String> names, Set<String> missing) {
        if (text == null || text.isEmpty() || text.indexOf("${env:") < 0) {
            return text == null ? "" : text;
        }
        Matcher m = ENV_TOKEN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            if (names != null) {
                names.add(name);
            }
            String v = lookup == null ? null : lookup.apply(name);
            if (v == null) {
                v = m.group(2);                 // ${env:X:-默认值}
                if (v == null) {
                    v = "";
                    if (missing != null) {
                        missing.add(name);
                    }
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public FilterMode mode() {
        return mode;
    }

    /** 这个子服在不在「要盯」的范围内（只看模式与名单，不看它有没有配 uuid） */
    public boolean selected(String serverName) {
        if (serverName == null) {
            return false;
        }
        String lower = serverName.toLowerCase(Locale.ROOT);
        boolean listed = false;
        for (String s : filterServers) {
            if (s.toLowerCase(Locale.ROOT).equals(lower)) {
                listed = true;
                break;
            }
        }
        return (mode == FilterMode.WHITELIST) == listed;
    }

    /** 按名字找已配置的目标；没配返回 null（名字大小写不敏感） */
    public Target target(String serverName) {
        return serverName == null ? null : targetsByLower.get(serverName.toLowerCase(Locale.ROOT));
    }

    public long intervalMillis() {
        return intervalMinutes * 60_000L;
    }

    public long graceMillis() {
        return startupGraceMinutes * 60_000L;
    }

    public long cooldownMillis() {
        return retryCooldownMinutes * 60_000L;
    }

    /** 取一条文案，支持 %key% 占位符：参数是 key、value 成对出现 */
    public String msg(String key, String... kv) {
        String raw = messages.get(key);
        if (raw == null || raw.isEmpty()) {
            raw = DEFAULT_MESSAGES.get(key);
        }
        if (raw == null) {
            return "";
        }
        String out = raw;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String token = kv[i];
            String value = kv[i + 1];
            if (token != null) {
                out = out.replace("%" + token + "%", value == null ? "" : value);
            }
        }
        return out;
    }
}
