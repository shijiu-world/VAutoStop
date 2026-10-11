package cn.shijiu.vautostop;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCSManager 接口的拼装与解读（只对接当前版本面板，路径与认证方式写死，不做老版本兼容）。
 *
 * <p>用到的两个接口：
 * <ul>
 *   <li><b>停实例</b>：{@code GET /api/protected_instance/stop?uuid=&daemonId=}</li>
 *   <li><b>查状态</b>：{@code GET /api/instance?uuid=&daemonId=}</li>
 * </ul>
 * 认证按官方文档（docs.mcsmanager.com → apis → get_apikey.md）：
 * <ul>
 *   <li>apikey 拼在 <b>URL 参数</b>上：{@code ?uuid=&daemonId=&apikey=}</li>
 *   <li>两个<b>必需</b>请求头：{@code X-Requested-With: XMLHttpRequest}、
 *       {@code Content-Type: application/json; charset=utf-8}（缺了会被判非法请求，直接 403）</li>
 *   <li>额外带 {@code Authorization: Bearer <apikey>}：面板同样认，纯冗余保险</li>
 * </ul>
 * ⚠ apikey 进了 URL → 任何打印 URL 的地方都必须先过 {@link #mask(String)}。
 *
 * <p>状态码（面板 InstanceDetail.status）：
 * <pre>
 *   -1 忙碌 / 0 停止 / 1 停止中 / 2 启动中 / 3 运行中
 * </pre>
 */
public final class McsmApi {

    /** 停止实例 */
    private static final String PATH_STOP = "/api/protected_instance/stop";

    /** 查询单个实例详情 */
    private static final String PATH_DETAIL = "/api/instance";

    private final VasConfig c;

    public McsmApi(VasConfig c) {
        this.c = c;
    }

    // ------------------------------------------------------------------
    // 地址
    // ------------------------------------------------------------------

    private String url(String path, String uuid, String daemonId) {
        StringBuilder sb = new StringBuilder(c.mcsm.url.trim());
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '/') {
            sb.setLength(sb.length() - 1);
        }
        sb.append(path);
        sb.append("?uuid=").append(enc(uuid));
        sb.append("&daemonId=").append(enc(daemonId));
        if (c.mcsm.apikey != null && !c.mcsm.apikey.isEmpty()) {
            sb.append("&apikey=").append(enc(c.mcsm.apikey));
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }

    public String daemonOf(VasConfig.Target t) {
        String d = t.daemonId;
        if (d == null || d.isEmpty()) {
            d = c.mcsm.daemonId;
        }
        return d == null ? "" : d;
    }

    public String stopUrl(VasConfig.Target t) {
        return url(PATH_STOP, t.uuid, daemonOf(t));
    }

    public String detailUrl(VasConfig.Target t) {
        return url(PATH_DETAIL, t.uuid, daemonOf(t));
    }

    /** 看着不像 MCSM 实例 uuid（32 位十六进制）就返回 false */
    public static boolean looksLikeUuid(String s) {
        return s != null && s.matches("(?i)[0-9a-f]{32}");
    }

    /**
     * 面板要求的请求头。前两个是<b>必需</b>头，缺了面板回的不是 401 而是 403；
     * 第三个 {@code Authorization} 是冗余保险。
     */
    public Map<String, String> authHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("Content-Type", "application/json; charset=utf-8");
        if (c.mcsm.apikey != null && !c.mcsm.apikey.isEmpty()) {
            h.put("Authorization", "Bearer " + c.mcsm.apikey);
        }
        return h;
    }

    /** 把文本里的密钥换成 *** —— URL 带上 apikey 后，任何输出 URL 的地方都得先过这里 */
    public String mask(String text) {
        String secret = c.mcsm.apikey;
        if (text == null) {
            return "";
        }
        if (secret == null || secret.isEmpty()) {
            return text;
        }
        return text.replace(secret, "***");
    }

    // ------------------------------------------------------------------
    // 响应解读
    // ------------------------------------------------------------------

    /**
     * 从 {@code /api/instance} 的响应里读实例状态码；读不到返回 null。
     * <pre>
     *   单条：{ "status":200, "data": { "instanceUuid":"x", "status":3 } }
     *   列表：{ "status":200, "data": { "data": [ { "instanceUuid":"x", "status":3 } ] } }
     * </pre>
     * ⚠ 顶层那个 "status":200 是 HTTP 层的状态码，别拿它当实例状态 —— 必须从 data 里取。
     */
    public Integer parseStatus(String body) {
        Object root = Json.parse(body);
        if (root == null) {
            return null;
        }
        // 先看 data.data[] 这种嵌套列表；本接口的返回里 data.status 就已经够了，
        // 两种形态都认只是为了面板哪天返回结构微调不至于整段解析失败。
        java.util.List<Object> arr = Json.array(Json.at(root, "data.data"));
        if (arr != null && !arr.isEmpty()) {
            Integer st = Json.integer(Json.at(arr.get(0), "status"));
            if (st != null) {
                return st;
            }
        }
        return Json.integer(Json.at(root, "data.status"));
    }

    public static String statusText(int s) {
        switch (s) {
            case -1: return "忙碌";
            case 0: return "停止";
            case 1: return "停止中";
            case 2: return "启动中";
            case 3: return "运行中";
            default: return "未知(" + s + ")";
        }
    }
}
