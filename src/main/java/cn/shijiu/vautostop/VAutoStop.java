package cn.shijiu.vautostop;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * VAutoStop —— 子服空服自动关机。
 *
 * <p>整个插件只装在 Velocity 代理端，子服一个插件都不用装：人数是代理自己管的
 * （{@code RegisteredServer#getPlayersConnected()}），停机是让 MCSManager 面板去执行，
 * 所以连被关的那个服上有没有插件都不重要。
 *
 * <p>主流程见 {@link IdleMonitor}：定时巡检 → 连续 N 次 0 人 → 查面板确认实例在运行
 * → 再数一遍人数（防止这会儿有人进来）→ 请求面板停止实例。
 */
@Plugin(
        id = "vautostop",
        name = "VAutoStop",
        // ⚠️ 版本号必须是编译期常量，没法直接写 ${project.version} —— 走 BuildConstants 间接引过来，
        //    它由 templating-maven-plugin 从 pom 生成（发版只改 pom 一处）。
        version = BuildConstants.VERSION,
        description = "子服空服自动关机：定期巡检人数，0 人到点就调 MCSManager 面板停实例。",
        authors = {"拾玖世界"}
)
public final class VAutoStop {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final HttpService http;
    private final IdleMonitor monitor;

    /** 构造时先塞一份内置默认（空配置），保证任何时候 config() 都不是 null。 */
    private volatile VasConfig config;

    @Inject
    public VAutoStop(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.http = new HttpService("ShijiuWorld-VAutoStop");
        this.config = VasConfig.load(Collections.emptyMap());
        this.monitor = new IdleMonitor(this, logger, http);
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        reload(null);
        registerCommands();
        logger.info("VAutoStop " + BuildConstants.VERSION
                + " 已就绪，命令 /vautostop（别名 /vas）。配置文件 plugins/vautostop/config.toml");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        monitor.shutdown();
        http.shutdown();
    }

    // ------------------------------------------------------------------
    // 命令
    // ------------------------------------------------------------------

    private void registerCommands() {
        CommandManager manager = proxy.getCommandManager();
        // ⚠️ 主名一律小写：Velocity 底层走 Brigadier，literal 节点大小写敏感。
        CommandMeta meta = manager.metaBuilder("vautostop")
                .aliases("vas")
                .plugin(this)
                .build();
        manager.register(meta, new AdminCommands(this));
    }

    /**
     * 回到主线程跑一段代码（Velocity 的调度器直接.test(.buildTask)就在主线程执行）。
     * 读人数、给玩家发消息都得走这里。
     */
    public void runOnMain(Runnable task) {
        try {
            proxy.getScheduler().buildTask(this, task).schedule();
        } catch (RuntimeException e) {
            // 插件卸载后调度会抛，兜住别把调用方搞崩
            logger.debug("调度失败：" + e.getMessage());
        }
    }

    public void send(CommandSource source, String legacyText) {
        if (source == null || legacyText == null || legacyText.isEmpty()) {
            return;
        }
        Component component = LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText);
        source.sendMessage(component);
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /** 重读 config.toml。读失败保留旧配置 —— 宁可用旧的，也不让插件变成半成品。 */
    public void reload(CommandSource feedback) {
        Path file = ensureConfigFile();
        try {
            VasConfig next = VasConfig.load(Toml.parse(file));
            config = next;
            report(next);
            monitor.restart(next);
            if (feedback != null) {
                send(feedback, config.msg("reloaded"));
            }
        } catch (Exception e) {
            logger.warn("config.toml 没读出来，继续用旧配置：" + e);
            if (feedback != null) {
                send(feedback, config.msg("reload-failed", "reason", String.valueOf(e)));
            }
        }
    }

    /** 第一次启动时把随包的默认模板放出来；已经存在就绝不动（改过的配置不能被覆盖） */
    private Path ensureConfigFile() {
        Path file = dataDirectory.resolve("config.toml");
        try {
            Files.createDirectories(dataDirectory);
            if (Files.exists(file)) {
                return file;
            }
            try (InputStream in = VAutoStop.class.getClassLoader().getResourceAsStream("config.toml")) {
                if (in != null) {
                    Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            logger.warn("创建默认 config.toml 失败：" + e.getMessage());
        }
        return file;
    }

    /** 起服/reload 后把关键配置打一遍 —— 省得改了半天不知道生没生效 */
    private void report(VasConfig c) {
        logger.info("总闸：" + (c.enabled ? "开" : "关（什么都不做）")
                + " ｜ 每 " + c.intervalMinutes + " 分钟巡检一次"
                + " ｜ 连续 " + c.requiredEmptyChecks + " 次 0 人才动手"
                + " ｜ 启动宽限 " + c.startupGraceMinutes + " 分钟"
                + " ｜ 重试冷却 " + c.retryCooldownMinutes + " 分钟");
        logger.info("过滤：" + (c.mode() == VasConfig.FilterMode.WHITELIST ? "白名单（只管列出的）"
                : "黑名单（列出的不管）")
                + (c.filterServers.isEmpty() ? "，名单为空" : " " + String.join(", ", c.filterServers)));
        logger.info("日志：过程性日志" + (c.verbose ? "开（verbose）" : "关（只在真的停机时打）") + " ｜ 细节可用 /vautostop list 看");
        logger.info("MCSM：" + (c.mcsm.enabled ? "联网" : "不联网（只写日志）")
                + " ｜ " + (c.mcsm.url.isEmpty() ? "⚠ url 没填" : c.mcsm.url)
                + " ｜ 停止前查状态=" + c.mcsm.verifyRunning
                + " ｜ dry-run=" + c.mcsm.dryRun);
        logger.info("受监控子服：" + (monitor.watched(c).isEmpty()
                ? "无" : String.join(", ", monitor.watched(c))));

        if (!c.envMissing.isEmpty()) {
            logger.warn("这些环境变量没设置，相关项按空处理：" + String.join(", ", c.envMissing)
                    + "（要在开服脚本里 export 再启动代理）");
        }
        List<String> bad = new ArrayList<>();
        for (VasConfig.Target t : c.targets.values()) {
            if (t.validUuids().isEmpty()) {
                bad.add(t.name + (t.enabled ? "" : "(已停用)"));
            }
        }
        if (!bad.isEmpty()) {
            logger.warn("这些子服没配可用的实例 uuid，盯到了也不会关："
                    + String.join(", ", bad) + "（uuid 是面板实例地址栏那串 32 位十六进制，可以写成列表）");
        }
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    public ProxyServer proxy() {
        return proxy;
    }

    public Logger logger() {
        return logger;
    }

    public HttpService http() {
        return http;
    }

    public IdleMonitor monitor() {
        return monitor;
    }

    public VasConfig config() {
        return config;
    }
}
