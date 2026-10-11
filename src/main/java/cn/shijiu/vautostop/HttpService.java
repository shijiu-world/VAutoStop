package cn.shijiu.vautostop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步 HTTP：所有请求都跑在自己的线程池上，绝不占 Velocity 主线程。
 * 用 JDK 自带的 {@code java.net.http.HttpClient}（Java 11+），不引第三方依赖。
 */
public final class HttpService {

    public static final class Result {
        public final boolean ok;
        public final int code;
        public final String body;

        Result(boolean ok, int code, String body) {
            this.ok = ok;
            this.code = code;
            this.body = body == null ? "" : body;
        }

        public boolean success2xx() {
            return ok && code >= 200 && code < 300;
        }
    }

    private final ExecutorService pool;
    private final HttpClient client;
    private final String userAgent;

    public HttpService(String userAgent) {
        this.userAgent = userAgent == null || userAgent.isEmpty() ? "VAutoStop" : userAgent;
        AtomicInteger n = new AtomicInteger(0);
        // 池子只用来"占一个线程、阻塞等 send() 返回"。巡检是逐个服串行发请求，
        // 4 个线程绰绰有余；真要并发（/vautostop test 那种）也不会排队等太久。
        this.pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "VAutoStop-HTTP-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        // 🔴 千万别给 HttpClient 设 .executor(pool)。
        // HttpClient 的 executor 是给它内部跑连接/读写用的，而我们的任务正阻塞在
        // send() 上等结果 —— 两边抢同一个固定池子会互相饿死：
        // 4 个线程全卡在 send()，内部任务没线程跑，请求只能等到超时才算完
        // （MiniGameVote 早期就这样把"查状态"变成必定失败）。
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 发起请求；返回的 Future 在线程池上完成，调用方自行 thenXxx 处理 */
    public CompletableFuture<Result> request(String url, long timeoutMs, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> doRequest(url, timeoutMs, headers), pool);
    }

    private Result doRequest(String url, long timeoutMs, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(500, timeoutMs)))
                    .header("User-Agent", userAgent)
                    .header("Accept", "*/*");

            // 自定义头放在默认头之后，允许覆盖
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && !e.getKey().isEmpty()) {
                        b.header(e.getKey(), e.getValue());
                    }
                }
            }

            b.GET();
            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Result(true, resp.statusCode(), resp.body());
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName();
            if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                msg += ": " + e.getMessage();
            }
            return new Result(false, -1, msg);
        }
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
