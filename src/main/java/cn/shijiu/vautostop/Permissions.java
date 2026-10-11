package cn.shijiu.vautostop;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;

/**
 * 权限节点清单。
 * <pre>
 *   /lp user <玩家> permission set vautostop.admin true
 *   /lp group admin permission set vautostop.* true
 * </pre>
 * Velocity 对没配过的权限返回「未定义」（不是「允许」），所以没有显式给的人用不了。
 */
public final class Permissions {

    /** 管理命令（list / check / test / reload）。控制台永远有。 */
    public static final String ADMIN = "vautostop.admin";

    private Permissions() {
    }

    /** 控制台 + 显式给了权限的人（OP 也要看有没有节点，Velocity 的 op 字段不管用）。 */
    public static boolean has(CommandSource source) {
        if (source instanceof ConsoleCommandSource) {
            return true;
        }
        if (source instanceof Player) {
            return source.hasPermission(ADMIN);
        }
        // 插件之类的其它来源：一律保守处理
        return source.hasPermission(ADMIN);
    }
}
