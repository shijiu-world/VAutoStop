package cn.shijiu.vautostop;

/**
 * 版本号常量，由 {@code templating-maven-plugin} 在 generate-sources 阶段
 * 用 pom.xml 的 {@code <version>} 生成。<b>别手改这个文件</b>，改了下次构建也会被覆盖。
 *
 * <p>存在的理由：{@code @Plugin} 注解的值必须是编译期常量，没法直接写
 * {@code ${project.version}}；而 velocity-api 的注解处理器又会按注解的值重写
 * {@code velocity-plugin.json}，写死成字面量就永远同步不了。
 */
public final class BuildConstants {

    /** 插件版本，等于 pom.xml 的 {@code <version>}。 */
    public static final String VERSION = "${project.version}";

    private BuildConstants() {
    }
}
