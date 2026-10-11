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

    public String stopUrl(String uuid, String daemonId) {
        return url(PATH_STOP, uuid, daemonId);
    }

    public String detailUrl(String uuid, String daemonId) {
        return url(PATH_DETAIL, uuid, daemonId);
    }

    public String stopUrl(VasConfig.Target t, String uuid) {
        return stopUrl(uuid, daemonOf(t));
    }

    public String detailUrl(VasConfig.Target t, String uuid) {
        return detailUrl(uuid, daemonOf(t));
    }

    /** 32 位 uuid 太长，日志/命令里只显示前 8 位就够定位了 */
    public static String shortUuid(String s) {
        return s == null ? "" : (s.length() > 8 ? s.substring(0, 8) : s);
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
     * 一次 {@code /api/instance} 查询的结论。
     *
     * <p>为什么不能只返回一个 Integer：uuid 列表里完全可能有「早就被删掉的实例」，
     * 那种情况下面板虽然回 HTTP 200，但 {@code data} 是 {@code null}（或压根没有 status 字段）。
     * 必须把它和「实例在，只是现在停着（status=0）」区分开 —— 前者说明配置写错了，
     * 要提醒服主清理；后者是正常现象，什么都不用说。判断是否该停，只看 {@link #running()}。
     */
    public static final class Probe {
        /** 实例状态码；null = 这次没能读出状态码（不等于"在跑"，一律按不在跑处理） */
        public final Integer status;
        /** status 为 null 时的原因；status 有值时为空串 */
        public final String reason;

        Probe(Integer status, String reason) {
            this.status = status;
            this.reason = reason == null ? "" : reason;
        }

        /** 只有明确读到「运行中(3)」才算在跑 —— 读不出来的一律不算 */
        public boolean running() {
            return status != null && status == 3;
        }

        /** 给日志/命令看的一句话结论 */
        public String describe() {
            return status != null ? statusText(status) : reason;
        }
    }

    /**
     * 读 {@code /api/instance} 的响应。
     * <pre>
     *   正常：  { "status":200, "data": { "instanceUuid":"x", "status":3 } }
     *   列表：  { "status":200, "data": { "data": [ { "instanceUuid":"x", "status":3 } ] } }
     *   实例不存在/没权限/面板出错：{ "status":200 或 500, "data": null }   ← 重点：HTTP 依然是 200
     * </pre>
     * ⚠ 顶层的 "status":200 是 HTTP 层的业务码，实例状态必须从 data 里取。
     */
    public static Probe probe(String body) {
        if (body == null || body.trim().isEmpty()) {
            return new Probe(null, "响应为空");
        }
        Object root = Json.parse(body);
        if (root == null) {
            return new Probe(null, "JSON 解析失败");
        }
        Integer top = Json.integer(Json.at(root, "status"));
        Object data = Json.at(root, "data");
        if (data == null) {
            // 🔴 uuid 不存在 / 密钥没权限看这个实例 / 面板报错，都是这个形态。
            //    千万别当成"实例在跑"，也别当成"实例停了"——这两种误判的代价都不小。
            String head = top == null ? "" : "面板业务码 " + top + "，";
            return new Probe(null, head + "没有实例数据（uuid 不存在或无权限）");
        }
        Integer status = null;
        java.util.List<Object> arr = Json.array(Json.at(data, "data"));
        if (arr != null && !arr.isEmpty()) {
            status = Json.integer(Json.at(arr.get(0), "status"));
        }
        if (status == null) {
            status = Json.integer(Json.at(data, "status"));
        }
        if (status == null) {
            return new Probe(null, "响应里没有实例状态码");
        }
        return new Probe(status, "");
    }

    /** @see #probe(String) */
    public Integer parseStatus(String body) {
        return probe(body).status;
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
