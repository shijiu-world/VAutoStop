package cn.shijiu.vautostop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析（零依赖）。
 *
 * <p>为什么自己写：只需要从 MCSManager 的响应里读 {@code data.status}、
 * {@code data.data[].instanceUuid / status} 这几个字段。为这点需求去依赖服务端里
 * 现成的 Gson，等于把插件的存亡押在"服务端一定带着某个库"上。
 *
 * <p>解析结果：对象 → {@link Map}，数组 → {@link List}，字符串 → {@link String}，
 * 数字 → {@link Long}/{@link Double}，布尔 → {@link Boolean}，null → null。
 *
 * <p>⚠ 解析失败一律返回 null，绝不让异常冒到调用方把巡检流程打断。
 */
public final class Json {

    private Json() {
    }

    /** 解析；格式不对返回 null */
    public static Object parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            Parser p = new Parser(text);
            p.skipWs();
            return p.value();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 按点分路径取值，如 "data.status"；取不到返回 null */
    public static Object at(Object root, String path) {
        Object cur = root;
        if (cur == null || path == null || path.isEmpty()) {
            return cur;
        }
        for (String key : path.split("\\.")) {
            if (cur instanceof Map) {
                cur = ((Map<?, ?>) cur).get(key);
            } else {
                return null;
            }
        }
        return cur;
    }

    public static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 取整数；不是数字就返回 null（别静默当 0 —— 状态码 0 是"已停止"，含义完全不同） */
    public static Integer integer(Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    // ------------------------------------------------------------------
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value() {
            skipWs();
            if (i >= s.length()) {
                throw new IllegalStateException("意外结束");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array0();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        private void expect(String word) {
            if (!s.startsWith(word, i)) {
                throw new IllegalStateException("期望 " + word);
            }
            i += word.length();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;                                    // 吃掉 '{'
            skipWs();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (i < s.length()) {
                skipWs();
                String key = string();
                skipWs();
                if (i < s.length() && s.charAt(i) == ':') {
                    i++;
                }
                m.put(key, value());
                skipWs();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                }
                break;
            }
            return m;
        }

        private List<Object> array0() {
            List<Object> list = new ArrayList<>();
            i++;                                    // 吃掉 '['
            skipWs();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (i < s.length()) {
                list.add(value());
                skipWs();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                }
                break;
            }
            return list;
        }

        private String string() {
            i++;                                    // 吃掉开头引号
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    break;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': {
                        if (i + 4 <= s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        break;
                    }
                    default: sb.append(e); break;
                }
            }
            return sb.toString();
        }

        private Object number() {
            int start = i;
            // 🔴 数字字符集一个都不能少：漏了某一位（比如 3 或 4），
            //    {"status": 3} 就会被判成非法 JSON → Json.parse 返回 null → 状态码永远读不到，
            //    而"这服是不是在运行"这种判断恰恰依赖它，错了会静默失效。
            while (i < s.length() && NUMBER_CHARS.indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String n = s.substring(start, i);
            if (n.isEmpty()) {
                throw new IllegalStateException("不是数字");
            }
            if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
                try {
                    return Long.valueOf(n);
                } catch (NumberFormatException ignored) {
                    // 落到 Double
                }
            }
            return Double.valueOf(n);
        }
    }

    private static final String NUMBER_CHARS = "+-0123456789.eE";
}
