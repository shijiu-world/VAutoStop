package cn.shijiu.vautostop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 TOML 解析（零依赖）。只认本插件 config.toml 用到的那点语法：
 * <pre>
 *   [section]              # 分节，支持 [a.b]
 *   key = "值"             # 引号可有可无
 *   key = true / 5         # 布尔、数字
 *   key = ["a", "b"]       # 单行数组
 *   key = [                # 多行数组（可以有行尾注释）
 *     "a",
 *     "b",
 *   ]
 * </pre>
 *
 * <p>结果是一张<b>扁平</b>表：分节名用点拼到键前面，
 * 例如 {@code [mcsm] timeout-ms = 8000} → {@code "mcsm.timeout-ms" = 8000L}。
 *
 * <p>⚠ 两个必须注意的点：
 * <ol>
 *   <li>行尾注释要<b>按引号状态</b>扫，否则 {@code enabled = true # 说明} 会把
 *       "true # 说明" 整个当字符串，布尔判断就翻成 false 了。</li>
 *   <li>取值一律 fail-safe：配错了退回默认值，绝不能因为一行写歪把插件搞挂。</li>
 * </ol>
 */
public final class Toml {

    private Toml() {
    }

    public static Map<String, Object> parse(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return parse(text);
    }

    public static Map<String, Object> parse(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        String prefix = "";

        for (int i = 0; i < lines.length; i++) {
            String line = stripComment(lines[i].trim());
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '[') {
                int end = line.lastIndexOf(']');
                if (end > 1) {
                    String section = unquote(line.substring(1, end).trim());
                    prefix = section.isEmpty() ? "" : section + ".";
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = unquote(line.substring(0, eq).trim());
            if (key.isEmpty()) {
                continue;
            }
            String valuePart = line.substring(eq + 1).trim();

            // 多行数组：这一行的 [ 没闭合，就把后面几行拼进来直到出现 ]
            if (valuePart.startsWith("[") && indexOfUnquoted(valuePart, ']') < 0) {
                StringBuilder sb = new StringBuilder(valuePart);
                while (sb.indexOf("]") < 0 && i + 1 < lines.length) {
                    i++;
                    sb.append(' ').append(stripComment(lines[i].trim()));
                }
                valuePart = sb.toString();
            }

            out.put(prefix + key, parseValue(valuePart));
        }
        return out;
    }

    /** 去掉行尾注释：只在没有被引号包住的情况下才认 # */
    private static String stripComment(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote && s.charAt(i - 1) != '\\') {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '#') {
                return s.substring(0, i).trim();
            }
        }
        return s;
    }

    private static int indexOfUnquoted(String s, char target) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote && s.charAt(i - 1) != '\\') {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == target) {
                return i;
            }
        }
        return -1;
    }

    private static Object parseValue(String value) {
        if (value.isEmpty()) {
            return "";
        }
        char first = value.charAt(0);
        if (first == '"' || first == '\'') {
            int end = endOfQuoted(value, first);
            String inner = end < 0 ? value.substring(1) : value.substring(1, end);
            return unescape(inner);
        }
        if (first == '[') {
            return parseArray(value);
        }
        return coerce(value);
    }

    private static int endOfQuoted(String value, char quote) {
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) == quote && value.charAt(i - 1) != '\\') {
                return i;
            }
        }
        return -1;
    }

    private static List<String> parseArray(String value) {
        List<String> list = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (c == quote && value.charAt(i - 1) != '\\') {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == ']') {
                break;
            }
            if (c == ',') {
                add(list, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        add(list, current);
        return list;
    }

    private static void add(List<String> list, StringBuilder s) {
        String item = s.toString().trim();
        if (!item.isEmpty()) {
            list.add(item);
        }
    }

    private static Object coerce(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            // 不是整数，接着试小数
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException ignored) {
            // 都不是那就当字符串
        }
        return unescape(value);
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case '\\': out.append('\\'); break;
                case '"': out.append('"'); break;
                case '\'': out.append('\''); break;
                default: out.append('\\').append(next); break;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // 取值工具：类型不对就退回默认值
    // ------------------------------------------------------------------

    public static String string(Map<String, Object> map, String key, String def) {
        Object o = map.get(key);
        return o == null ? def : String.valueOf(o);
    }

    public static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object o = map.get(key);
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o != null) {
            return Boolean.parseBoolean(String.valueOf(o).trim());
        }
        return def;
    }

    public static int integer(Map<String, Object> map, String key, int def) {
        Object o = map.get(key);
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
                // 写歪了走默认
            }
        }
        return def;
    }

    public static List<String> list(Map<String, Object> map, String key) {
        Object o = map.get(key);
        List<String> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object item : (List<?>) o) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else if (o != null && !String.valueOf(o).isEmpty()) {
            out.add(String.valueOf(o));
        }
        return out;
    }
}
