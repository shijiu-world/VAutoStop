package cn.shijiu.vautostop;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 离线自测（不开服）：只用 target/classes，不需要 velocity-api。
 *
 * <pre>
 *   cd 项目根目录
 *   javac -encoding UTF-8 -cp target/classes -d target/test-classes tests/VasTest.java
 *   java -cp target/classes;target/test-classes cn.shijiu.vautostop.VasTest
 * </pre>
 */
public final class VasTest {

    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";


    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Map<String, Object> table = Toml.parse(configFile());

        // ---------- 随包模板的基本默认值 ----------
        VasConfig c = VasConfig.load(table);
        check("随包模板 enabled = true", c.enabled);
        check("随包模板间隔 10 分钟", c.intervalMinutes == 10);
        check("随包模板连续次数默认 2", c.requiredEmptyChecks == 2);
        check("随包模板停机前查状态 = true", c.mcsm.verifyRunning);
        check("随包模板 dry-run = false", !c.mcsm.dryRun);
        check("随包模板宽限 3 分钟", c.startupGraceMinutes == 3);
        check("随包模板冷却 5 分钟", c.retryCooldownMinutes == 5);
        check("宽限/冷却换算成毫秒", c.graceMillis() == 180_000L && c.cooldownMillis() == 300_000L);
        // ---------- 冷却窗口：「从没尝试过」不能被当成「刚尝试过」----------
        long cd = 300_000L;                       // 5 分钟
        long twoMin = 120_000_000_000L;           // 代理跑了 2 分钟（模拟刚开机时 nanoTime 很小）
        check("从没尝试过不算冷却中", !VasConfig.inCooldown(-1L, twoMin, cd));
        check("刚尝试过算冷却中", VasConfig.inCooldown(twoMin - 60_000_000_000L, twoMin, cd));
        check("超过冷却窗口就放行", !VasConfig.inCooldown(twoMin - 400_000_000_000L, twoMin, cd));
        check("冷却为 0 分钟时永不算冷却中", !VasConfig.inCooldown(twoMin, twoMin, 0L));

        // ---------- 连续次数的铁律 ----------
        Map<String, Object> one = new LinkedHashMap<>(table);
        one.put("required-empty-checks", 1L);
        check("配成 1 次也会被夹成 2 次", VasConfig.load(one).requiredEmptyChecks == 2);

        Map<String, Object> zero = new LinkedHashMap<>(table);
        zero.put("required-empty-checks", 0L);
        check("配成 0 次也会被夹成 2 次", VasConfig.load(zero).requiredEmptyChecks == 2);

        Map<String, Object> many = new LinkedHashMap<>(table);
        many.put("required-empty-checks", 4L);
        check("配成 4 次就照 4 次", VasConfig.load(many).requiredEmptyChecks == 4);

        // ---------- 白名单 / 黑名单 ----------
        check("黑名单：lobby 被排除", !c.selected("lobby"));
        check("黑名单：killer 参与", c.selected("killer"));
        check("黑名单：大小写不敏感", !c.selected("LoBbY") && c.selected("KILLER"));
        check("黑名单：没列出的也参与", c.selected("minigame"));

        Map<String, Object> white = new LinkedHashMap<>(table);
        white.put("filter.mode", "whitelist");
        white.put("filter.servers", new ArrayList<>(java.util.List.of("killer", "BEDWARS")));
        VasConfig wc = VasConfig.load(white);
        check("白名单：列了才参与", wc.selected("killer") && wc.selected("bedwars"));
        check("白名单：没列的都不参与", !wc.selected("survival") && !wc.selected("lobby"));

        Map<String, Object> whiteEmpty = new LinkedHashMap<>(table);
        whiteEmpty.put("filter.mode", "whitelist");
        whiteEmpty.put("filter.servers", new ArrayList<>());
        VasConfig we = VasConfig.load(whiteEmpty);
        check("白名单空 = 谁都不盯（安全侧）", !we.selected("killer") && !we.selected("survival"));

        // ---------- 子服配置（[servers.*]）----------
        check("解析出 4 个 [servers.*] 分节", c.targets.size() == 4);
        check("按名字找目标（大小写不敏感）", c.target("KILLER") != null && c.target("killer") == c.target("KILLER"));
        check("bedwars 配了 enabled=false", !c.target("bedwars").enabled);
        check("32 位十六进制才算合法 uuid", McsmApi.looksLikeUuid(UUID_A));
        check("分节名提取", VasConfig.serverSectionNames(table).contains("killer")
                && VasConfig.serverSectionNames(table).size() == 4);

        // ---------- uuid 列表（同一端口挂多个实例）----------
        check("随包模板里的 uuid 是占位值 → 没有一个合法", c.target("killer").validUuids().isEmpty());
        check("随包模板 killer 写的是 2 个占位 uuid", c.target("killer").uuids.size() == 2);
        check("占位值本身不合法", !McsmApi.looksLikeUuid(c.target("killer").uuids.get(0)));

        Map<String, Object> single = new LinkedHashMap<>(table);
        single.put("servers.killer.uuid", UUID_A);
        VasConfig sc = VasConfig.load(single);
        check("单个字符串写法也当成一个元素的列表", sc.target("killer").uuids.size() == 1
                && UUID_A.equals(sc.target("killer").uuids.get(0)));

        Map<String, Object> dup = new LinkedHashMap<>(table);
        dup.put("servers.killer.uuid", new ArrayList<>(java.util.List.of(UUID_A, UUID_A, UUID_B)));
        VasConfig dc = VasConfig.load(dup);
        check("uuid 去重且保持顺序", dc.target("killer").uuids.size() == 2
                && UUID_A.equals(dc.target("killer").uuids.get(0))
                && UUID_B.equals(dc.target("killer").uuids.get(1)));

        Map<String, Object> mixed = new LinkedHashMap<>(table);
        mixed.put("servers.killer.uuid", new ArrayList<>(java.util.List.of(UUID_A, "实例uuid", UUID_B)));
        check("validUuids 只留合法的那几个",
                VasConfig.load(mixed).target("killer").validUuids().equals(java.util.List.of(UUID_A, UUID_B)));
        // ⚠ 别照着终端抄这串数字：要改就用 PowerShell 打 [int]$c 核对，屏幕上的顺序可能是乱的
        check("uuid 缩写成前 8 位", "01234567".equals(McsmApi.shortUuid(UUID_A)));

        // ---------- 多行 uuid 列表（服主写列表时最常见的写法，必须能解析） ----------
        Map<String, Object> ml = Toml.parse(
                "[servers.killer]\n"
                        + "uuid = [\n"
                        + "    \"" + UUID_A + "\",   # 一号游戏\n"
                        + "    \"" + UUID_B + "\",\n"
                        + "]\n"
                        + "enabled = true   # 注释里带 ] 和 ,\n");
        VasConfig mlc = VasConfig.load(ml);
        check("多行 uuid 列表能解析出来", mlc.target("killer").uuids.equals(java.util.List.of(UUID_A, UUID_B)));
        check("多行列表里的行尾注释没串进值",
                McsmApi.looksLikeUuid(mlc.target("killer").uuids.get(0)));
        check("多行列表后面紧跟的 enabled 没被吃掉", mlc.target("killer").enabled);
        check("多行列表的多个实例都能拼出查状态地址",
                mlc.target("killer").validUuids().size() == 2);

        // ---------- 环境变量 ----------
        Map<String, String> fake = new LinkedHashMap<>();
        fake.put("VAS_TEST_KEY", "secret-123");
        Function<String, String> lookup = fake::get;
        check("env 展开", "secret-123".equals(VasConfig.env("${env:VAS_TEST_KEY}", lookup, null, null)));
        check("env 兜底值", "fallback".equals(VasConfig.env("${env:NOT_SET:-fallback}", lookup, null, null)));
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        VasConfig.env("${env:MISSING_KEY}", lookup, null, missing);
        check("env 缺失记进 missing", missing.contains("MISSING_KEY"));

        // ---------- JSON：面板状态码 ----------
        McsmApi api = new McsmApi(c);
        check("读 data.status = 3",
                Integer.valueOf(3).equals(api.parseStatus("{\"status\":200,\"data\":{\"instanceUuid\":\"x\",\"status\":3}}")));
        // 🔴 这里是以前踩过的坑：数字字符集漏了 3/4，会把 {"status":3} 判成非法 JSON → 返回 null
        check("0 不是 null（停止）",
                Integer.valueOf(0).equals(api.parseStatus("{\"status\":200,\"data\":{\"status\":0}}")));
        check("负数状态（忙碌 -1）",
                Integer.valueOf(-1).equals(api.parseStatus("{\"status\":200,\"data\":{\"status\":-1}}")));
        check("列表形态也能读",
                Integer.valueOf(3).equals(api.parseStatus(
                        "{\"status\":200,\"data\":{\"data\":[{\"instanceUuid\":\"x\",\"status\":3}]}}")));
        check("瞎写的响应返回 null", api.parseStatus("<html>面板出错</html>") == null);
        check("顶层 HTTP status 不被当成实例状态",
                !Integer.valueOf(200).equals(api.parseStatus("{\"status\":200}")));

        // ---------- Probe：实例不存在的各种形态 ----------
        McsmApi.Probe running = McsmApi.probe("{\"status\":200,\"data\":{\"status\":3}}");
        check("明确的运行中(3) → running", running.running() && Integer.valueOf(3).equals(running.status));
        McsmApi.Probe stopped = McsmApi.probe("{\"status\":200,\"data\":{\"status\":0}}");
        check("明确的停止(0) → 不在跑", !stopped.running() && "停止".equals(stopped.describe()));
        McsmApi.Probe gone = McsmApi.probe("{\"status\":200,\"data\":null}");
        check("uuid 不存在（data=null）不算运行中", !gone.running() && gone.status == null);
        check("uuid 不存在要给出人话原因", gone.reason.contains("没有实例数据"));
        McsmApi.Probe err500 = McsmApi.probe("{\"status\":500,\"data\":null}");
        check("面板业务码非 200 也算查不出来", !err500.running() && err500.reason.contains("500"));
        McsmApi.Probe empty = McsmApi.probe("");
        check("空响应不算运行中", !empty.running() && "响应为空".equals(empty.reason));
        McsmApi.Probe junk = McsmApi.probe("<html>");
        check("非 JSON 不算运行中", !junk.running() && "JSON 解析失败".equals(junk.reason));
        McsmApi.Probe nodata = McsmApi.probe("{\"status\":200,\"data\":{\"instanceUuid\":\"x\"}}");
        check("有 data 但没 status 字段 → 不算运行中", !nodata.running() && nodata.status == null);

        // ---------- 面板地址与打码 ----------
        Map<String, Object> real = new LinkedHashMap<>(table);
        real.put("mcsm.url", "http://panel.example:23333/");
        real.put("mcsm.daemon-id", "daemon-uuid");
        real.put("mcsm.apikey", "TOPSECRET");
        real.put("servers.killer.uuid", new ArrayList<>(java.util.List.of(UUID_A, UUID_B)));
        VasConfig rc = VasConfig.load(real);
        McsmApi rapi = new McsmApi(rc);
        VasConfig.Target killer = rc.target("killer");

        check("结尾多余的斜杠被去掉",
                rapi.stopUrl(killer, UUID_A).startsWith("http://panel.example:23333/api/protected_instance/stop?"));
        check("停实例路径与参数",
                rapi.stopUrl(killer, UUID_A).contains("uuid=" + UUID_A)
                        && rapi.stopUrl(killer, UUID_A).contains("daemonId=daemon-uuid"));
        check("查状态路径", rapi.detailUrl(killer, UUID_A).contains("/api/instance?"));
        check("apikey 拼在 URL 上", rapi.stopUrl(killer, UUID_A).contains("apikey=TOPSECRET"));
        check("打码：URL 里的密钥变成 ***",
                !rapi.mask(rapi.stopUrl(killer, UUID_A)).contains("TOPSECRET")
                        && rapi.mask(rapi.stopUrl(killer, UUID_A)).contains("***"));
        check("必需请求头齐全",
                "XMLHttpRequest".equals(rapi.authHeaders().get("X-Requested-With"))
                        && "Bearer TOPSECRET".equals(rapi.authHeaders().get("Authorization"))
                        && rapi.authHeaders().get("Content-Type").startsWith("application/json"));
        Map<String, Object> noKey = new LinkedHashMap<>(real);
        noKey.put("mcsm.apikey", "");
        McsmApi nk = new McsmApi(VasConfig.load(noKey));
        check("没配密钥就不带 apikey 参数", !nk.stopUrl(killer, UUID_A).contains("apikey="));
        // ---------- apikey 也支持 ${env:XX:-兜底值} ----------
        Map<String, Object> fallback = new LinkedHashMap<>(real);
        fallback.put("mcsm.apikey", "${env:VAS_API_KEY:-fallback-secret}");
        VasConfig fc = VasConfig.load(fallback);
        check("apikey 能取 env 兜底值", "fallback-secret".equals(fc.mcsm.apikey));
        check("用了兜底值不算 missing", !fc.envMissing.contains("VAS_API_KEY"));
        check("兜底值会拼进 URL", new McsmApi(fc).stopUrl(fc.target("killer"), UUID_A)
                .contains("apikey=fallback-secret"));

        // 优先级要在 env() 展开器上验：load() 走的是真实系统环境变量，测试里改不动
        fake.put("PREC_KEY", "real-secret");
        check("环境变量存在时优先于兜底值",
                "real-secret".equals(VasConfig.env("${env:PREC_KEY:-fallback-secret}",
                        k -> fake.get(k), null, null)));
        check("环境变量没设才落到兜底值",
                "fallback-secret".equals(VasConfig.env("${env:NOPE_KEY:-fallback-secret}",
                        k -> fake.get(k), null, null)));
        Set<String> miss = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        VasConfig.env("${env:TOTALLY_ABSENT}", k -> fake.get(k), names, miss);
        check("没兜底值又没环境变量 → 展开成空", "".equals(
                VasConfig.env("${env:TOTALLY_ABSENT}", k -> fake.get(k), null, null)));
        check("没兜底值的情况会被记进 missing", miss.contains("TOTALLY_ABSENT")
                && names.contains("TOTALLY_ABSENT"));
        check("同一个值里多处 ${env:} 都能展开", "x-real-secret-y-real-secret".equals(
                VasConfig.env("x-${env:PREC_KEY}-y-${env:PREC_KEY}", k -> fake.get(k), null, null)));

        // ---------- 文案 ----------
        check("缺 key 时用内置默认", c.msg("list-none").contains("没有符合过滤条件"));
        String line = c.msg("list-next", "next", "7");
        check("占位符替换", line.contains("7") && !line.contains("%next%"));

        System.out.println();
        System.out.println("结果：通过 " + passed + " 项，失败 " + failed + " 项");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static Path configFile() {
        String[] candidates = {"src/main/resources/config.toml", "../src/main/resources/config.toml"};
        for (String s : candidates) {
            Path p = Paths.get(s);
            if (java.nio.file.Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException("找不到 config.toml，请在项目根目录运行");
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "[通过] " : "[失败] ") + name);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }

    private VasTest() {
    }
}
