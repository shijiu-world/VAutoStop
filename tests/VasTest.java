package cn.shijiu.vautostop;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
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
        check("enable/interval 换算成毫秒", c.intervalMillis() == 600_000L && c.cooldownMillis() == 300_000L);

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
        check("随包模板里的 uuid 是占位值 → 不合法", !McsmApi.looksLikeUuid(c.target("killer").uuid));
        check("32 位十六进制才算合法 uuid", McsmApi.looksLikeUuid("abcdef0123456789abcdef0123456789"));
        check("分节名提取", VasConfig.serverSectionNames(table).contains("killer")
                && VasConfig.serverSectionNames(table).size() == 4);

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

        // ---------- 面板地址与打码 ----------
        Map<String, Object> real = new LinkedHashMap<>(table);
        real.put("mcsm.url", "http://panel.example:23333/");
        real.put("mcsm.daemon-id", "daemon-uuid");
        real.put("mcsm.apikey", "TOPSECRET");
        real.put("servers.killer.uuid", "abcdef0123456789abcdef0123456789");
        VasConfig rc = VasConfig.load(real);
        McsmApi rapi = new McsmApi(rc);
        VasConfig.Target killer = rc.target("killer");

        check("结尾多余的斜杠被去掉",
                rapi.stopUrl(killer).startsWith("http://panel.example:23333/api/protected_instance/stop?"));
        check("停实例路径与参数",
                rapi.stopUrl(killer).contains("uuid=abcdef0123456789abcdef0123456789")
                        && rapi.stopUrl(killer).contains("daemonId=daemon-uuid"));
        check("查状态路径", rapi.detailUrl(killer).contains("/api/instance?"));
        check("apikey 拼在 URL 上", rapi.stopUrl(killer).contains("apikey=TOPSECRET"));
        check("打码：URL 里的密钥变成 ***",
                !rapi.mask(rapi.stopUrl(killer)).contains("TOPSECRET")
                        && rapi.mask(rapi.stopUrl(killer)).contains("***"));
        check("必需请求头齐全",
                "XMLHttpRequest".equals(rapi.authHeaders().get("X-Requested-With"))
                        && "Bearer TOPSECRET".equals(rapi.authHeaders().get("Authorization"))
                        && rapi.authHeaders().get("Content-Type").startsWith("application/json"));
        Map<String, Object> noKey = new LinkedHashMap<>(real);
        noKey.put("mcsm.apikey", "");
        McsmApi nk = new McsmApi(VasConfig.load(noKey));
        check("没配密钥就不带 apikey 参数", !nk.stopUrl(killer).contains("apikey="));

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
