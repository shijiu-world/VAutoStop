package cn.shijiu.vautostop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 面板 apikey 的对称加解密（AES-256-GCM）。
 *
 * <p>干什么用：config.toml 里不想落明文密钥时，先用配套网页把明文加密成
 * {@code ENC1:<base64>} 再填进去，插件读配置时自动还原成明文去调面板。
 *
 * <p>格式：{@code ENC1:} + Base64( IV(12 字节) || 密文+GCM tag(16 字节) )。
 * 每次加密都换随机 IV，所以同一个明文每次出来的串都不一样 —— 这是正常的。
 *
 * <p>⚠ 定位说清楚：种子就编在插件里，拿到 jar 的人理论上都能还原。
 * 这套东西防的是「配置文件被随手拷走 / 截图外传 / 丢进公开仓库」这类泄露，
 * 不是防逆向。真要看住密钥，仍然优先用 {@code ${env:XXX}} 环境变量。
 */
public final class KeyCrypto {

    /** 密文前缀。配置里带这个前缀的字面量才会被当作密文解；没有前缀的一律原样使用。 */
    public static final String PREFIX = "ENC1:";

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String ALGO = "AES/GCM/NoPadding";

    /**
     * 种子（混淆存放：拆成三段 base64，运行时拼起来再和掩码逐字节异或）。
     * 别把它提交到任何公开仓库之外的明文版本 —— 这里已经是混淆后的了。
     */
    private static final String[] SEED_PARTS = {
            "TykcRT8rPldVbA5R",
            "OwtzWnpGB0MDDwcg",
            "H1AtIGoIIRhQFVcA",
    };
    private static final String SEED_MASK = "vautostop-idle-2026";

    /** 最近一次解密失败的原因（成功/未命中密文时置空）。给启动告警用，别拿它做判断分支。 */
    private static volatile String lastError;

    private KeyCrypto() {
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * 加密（自检 / 单元测试用；插件运行时只解不解）。
     *
     * @return {@code ENC1:...}；失败返回 null
     */
    public static String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = Arrays.copyOf(iv, IV_LEN + ct.length);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解密。不是 {@code ENC1:} 开头 → 原样返回（明文照用，环境变量取回来的值也走这条路）。
     *
     * <p>是密文但解不开（种子不对 / 串被截短 / 被人改过）→ 返回空串并记 {@link #lastError()}，
     * 绝不解出半个错的密钥发给面板。
     */
    public static String decrypt(String text) {
        if (text == null || text.isEmpty()) {
            lastError = null;
            return text == null ? "" : text;
        }
        String v = text.trim();
        if (!v.startsWith(PREFIX)) {
            lastError = null;
            return text;
        }
        String body = v.substring(PREFIX.length()).trim();
        try {
            byte[] all = Base64.getDecoder().decode(body);
            if (all.length <= IV_LEN) {
                lastError = "密文太短（" + all.length + " 字节），像是被截断了";
                return "";
            }
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOf(all, IV_LEN)));
            byte[] plain = c.doFinal(all, IV_LEN, all.length - IV_LEN);
            lastError = null;
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            lastError = "不是合法 base64（复制时缺了字符？）";
            return "";
        } catch (Exception e) {
            // GCM tag 校验失败 = 种子不对或串被改过，javax.crypto.AEADBadTagException 走这里
            lastError = "解密失败（种子对不上 / 密文被改过）：" + e.getClass().getSimpleName();
            return "";
        }
    }

    /** 判断一个配置值是不是密文（给只显示状态的场合用，别把值打出来） */
    public static boolean isEncrypted(String text) {
        return text != null && text.trim().startsWith(PREFIX);
    }

    private static SecretKeySpec key() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        return new SecretKeySpec(sha.digest(seed().getBytes(StandardCharsets.UTF_8)), "AES");
    }

    private static String seed() {
        StringBuilder sb = new StringBuilder();
        for (String p : SEED_PARTS) {
            sb.append(p);
        }
        byte[] raw = Base64.getDecoder().decode(sb.toString());
        byte[] mask = SEED_MASK.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = (byte) (raw[i] ^ mask[i % mask.length]);
        }
        return new String(out, StandardCharsets.UTF_8);
    }
}
