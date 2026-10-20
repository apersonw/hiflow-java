package io.github.apersonw.hiflow;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * HiFlow 流程引擎 Open API 客户端：请求签名、发起流程、待办查询与处理。线程安全，可全局复用。
 *
 * <pre>{@code
 * var client = HiFlowClient.builder()
 *         .baseUrl("https://flow.example.com/api")
 *         .credentials(System.getenv("HIFLOW_CLIENT_ID"), System.getenv("HIFLOW_CLIENT_SECRET"))
 *         .build();
 * var instance = client.start(StartRequest.builder("leave").businessKey("L-1001").starter("u42")
 *         .variable("days", 3).variable("manager", "u7").build());
 * var todo = client.todo("u7");
 * client.complete(todo.list().get(0).id(), "u7", "同意", Map.of("approved", true));
 * }</pre>
 *
 * 服务端错误抛出 {@link HiFlowException}，网络错误抛出 {@link UncheckedIOException}。
 */
public final class HiFlowClient {
    public static final String VERSION = "0.1.1";

    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final HttpClient http;
    private final String userAgent;
    private final int retries;
    private final Duration requestTimeout;
    private final Duration backoff;
    /** 本地时钟与服务端的偏差（毫秒），时间戳超窗时按服务端时间校准 */
    final AtomicLong skewMillis = new AtomicLong();
    LongSupplier clock = System::currentTimeMillis;

    private HiFlowClient(Builder b) {
        baseUrl = b.baseUrl;
        clientId = b.clientId;
        clientSecret = b.clientSecret;
        http = b.httpClient != null ? b.httpClient
                : HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
        userAgent = b.userAgent;
        retries = b.retries;
        requestTimeout = b.requestTimeout;
        backoff = b.backoff;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------- 流程定义 ----------

    /** 流程定义的最新版本（含流程图） */
    public Definition definition(String key) {
        return Definition.from(object(call("GET", "/open/v1/definitions/" + Objects.requireNonNull(key, "key"), Map.of(), null)));
    }

    // ---------- 流程实例 ----------

    /** 发起流程实例，返回实例详情（含发起后生成的待办） */
    public Instance start(StartRequest request) {
        Objects.requireNonNull(request, "request");
        return Instance.from(object(call("POST", "/open/v1/instances", Map.of(), request.toBody())));
    }

    /** 实例详情（含全部任务与流程轨迹） */
    public Instance instance(long id) {
        return Instance.from(object(call("GET", "/open/v1/instances/" + id, Map.of(), null)));
    }

    /** 查询一页流程实例（不含任务与轨迹） */
    public Page<Instance> instances(InstanceQuery query) {
        var q = query == null ? Map.<String, List<String>>of() : query.toQuery();
        return Page.from(object(call("GET", "/open/v1/instances", q, null)), Instance::from);
    }

    /** 取消流程实例（撤回 / 终止），进行中的任务一并取消 */
    public Instance cancel(long id, String operator, String reason) {
        var body = new LinkedHashMap<String, Object>();
        putIfPresent(body, "operator", operator);
        putIfPresent(body, "reason", reason);
        return Instance.from(object(call("POST", "/open/v1/instances/" + id + "/cancel", Map.of(), body)));
    }

    /**
     * 发起人撤回：还没有任何任务被处理时可撤回，operator 须为实例的发起人
     * （否则 {@link ErrorCodes#NOT_ASSIGNEE}；已有任务处理过为 {@link ErrorCodes#RECALL_NOT_ALLOWED}）。
     */
    public Instance recall(long id, String operator) {
        return Instance.from(object(call("POST", "/open/v1/instances/" + id + "/recall", Map.of(),
                Map.of("operator", Objects.requireNonNull(operator, "operator")))));
    }

    // ---------- 任务 ----------

    /** 查询一页任务 */
    public Page<Task> tasks(TaskQuery query) {
        var q = query == null ? Map.<String, List<String>>of() : query.toQuery();
        return Page.from(object(call("GET", "/open/v1/tasks", q, null)), Task::from);
    }

    /** 用户的待办第一页（最多 100 条）：处理人是他，或未指定处理人且他在候选人中 */
    public Page<Task> todo(String user) {
        return tasks(TaskQuery.builder().user(Objects.requireNonNull(user, "user")).status(Task.ACTIVE).pageSize(100).build());
    }

    /** 任务详情 */
    public Task task(long id) {
        return Task.from(object(call("GET", "/open/v1/tasks/" + id, Map.of(), null)));
    }

    /**
     * 完成任务，流程按模型继续推进；返回推进后的实例详情。
     *
     * @param operator  处理人，须为任务的处理人或候选人之一
     * @param variables 合并到流程变量（如审批结果 approved），由排他网关的条件决定流程走向；可为 null。
     *                  会签任务的 approved 记为本人表态，节点结束时流程变量 approved 为会签结论
     */
    public Instance complete(long taskId, String operator, String comment, Map<String, ?> variables) {
        var body = new LinkedHashMap<String, Object>();
        body.put("operator", Objects.requireNonNull(operator, "operator"));
        putIfPresent(body, "comment", comment);
        if (variables != null && !variables.isEmpty()) {
            body.put("variables", variables);
        }
        return Instance.from(object(call("POST", "/open/v1/tasks/" + taskId + "/complete", Map.of(), body)));
    }

    /** 认领候选任务：认领后 user 成为处理人 */
    public Task claim(long taskId, String user) {
        return Task.from(object(call("POST", "/open/v1/tasks/" + taskId + "/claim", Map.of(), Map.of("user", user))));
    }

    /** 可退回的节点：本实例中处理过、且在当前分支或其外层的人工任务（最近处理的在前） */
    public List<ReturnTarget> returnTargets(long taskId) {
        var out = new java.util.ArrayList<ReturnTarget>();
        for (var v : (List<?>) call("GET", "/open/v1/tasks/" + taskId + "/return-targets", Map.of(), null)) {
            out.add(ReturnTarget.from(Json.asObject(v)));
        }
        return List.copyOf(out);
    }

    /**
     * 退回到本实例中处理过的人工任务节点：本待办结束，目标节点重新生成待办；目标在当前并行分支之外时整次并行分支作废。
     * 目标不合法时抛出 {@link ErrorCodes#RETURN_TARGET}。
     */
    public Instance returnTask(long taskId, String operator, String targetNode, String comment) {
        var body = new LinkedHashMap<String, Object>();
        body.put("operator", Objects.requireNonNull(operator, "operator"));
        body.put("target_node", Objects.requireNonNull(targetNode, "targetNode"));
        putIfPresent(body, "comment", comment);
        return Instance.from(object(call("POST", "/open/v1/tasks/" + taskId + "/return", Map.of(), body)));
    }

    /** 转办：把任务交给其他人处理 */
    public Task transfer(long taskId, String operator, String to, String comment) {
        var body = new LinkedHashMap<String, Object>();
        body.put("operator", Objects.requireNonNull(operator, "operator"));
        body.put("to", Objects.requireNonNull(to, "to"));
        putIfPresent(body, "comment", comment);
        return Task.from(object(call("POST", "/open/v1/tasks/" + taskId + "/transfer", Map.of(), body)));
    }

    // ---------- 传输 ----------

    long now() {
        return clock.getAsLong() + skewMillis.get();
    }

    /** path 为未编码的服务端路径（参与签名），拼接 URL 时逐段编码 */
    private HttpRequest request(String method, String path, Map<String, List<String>> query, byte[] body) {
        var url = new StringBuilder(baseUrl);
        for (var seg : path.substring(1).split("/", -1)) {
            url.append('/').append(Signer.escape(seg));
        }
        if (!query.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (var e : query.entrySet()) {
                for (var v : e.getValue()) {
                    if (!first) {
                        url.append('&');
                    }
                    first = false;
                    url.append(Signer.escape(e.getKey())).append('=').append(Signer.escape(v));
                }
            }
        }
        var b = HttpRequest.newBuilder(URI.create(url.toString()));
        if (requestTimeout != null) {
            b.timeout(requestTimeout);
        }
        String hash = Signer.EMPTY_SHA256;
        if (body != null) {
            hash = Signer.sha256Hex(body);
            b.header("Content-Type", "application/json");
            b.method(method, BodyPublishers.ofByteArray(body));
        } else {
            b.method(method, BodyPublishers.noBody());
        }
        String ts = Long.toString(now() / 1000);
        String nonce = Signer.newNonce();
        return b.header("User-Agent", userAgent)
                .header("X-Client-Id", clientId)
                .header("X-Timestamp", ts)
                .header("X-Nonce", nonce)
                .header("X-Content-SHA256", hash)
                .header("X-Signature", Signer.sign(clientSecret, Signer.stringToSign(method, path, query, clientId, ts, nonce, hash)))
                .build();
    }

    /**
     * 发送请求并返回 data：GET 在网络错误与 429 / 502 / 503 / 504 时按配置重试（每次重新签名）；
     * 时间戳超窗时按服务端时间校准时钟后立即重试一次（超窗请求在验签前即被拒绝，写操作不会重复执行）。
     */
    Object call(String method, String path, Map<String, List<String>> query, Object body) {
        byte[] payload = body == null ? null : Json.write(body).getBytes(StandardCharsets.UTF_8);
        int attempts = 1 + ("GET".equals(method) ? retries : 0);
        boolean clockFixed = false;
        long waitMs = 0;
        IOException last = null;
        for (int i = 0; i < attempts; i++) {
            if (waitMs > 0) {
                sleep(waitMs);
            }
            waitMs = backoff.toMillis() << i;
            HttpResponse<byte[]> resp;
            try {
                resp = http.send(request(method, path, query, payload), BodyHandlers.ofByteArray());
            } catch (IOException e) {
                last = e;
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UncheckedIOException(new InterruptedIOException("hiflow: interrupted"));
            }
            int status = resp.statusCode();
            if (status == 401 && !clockFixed && syncClock(resp.body())) {
                clockFixed = true;
                attempts++;
                waitMs = 0;
                continue;
            }
            if (retryable(status) && i < attempts - 1) {
                var retryAfter = resp.headers().firstValue("Retry-After");
                if (retryAfter.isPresent()) {
                    try {
                        waitMs = Math.min(Math.max(waitMs, Long.parseLong(retryAfter.get()) * 1000), 30_000);
                    } catch (NumberFormatException ignored) {
                        // 非秒数格式：按指数退避
                    }
                }
                continue;
            }
            return decode(status, resp.body());
        }
        throw new UncheckedIOException("hiflow: " + method + " " + path, last != null ? last : new IOException("retries exhausted"));
    }

    private static boolean retryable(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    /** 401 响应为时间戳超窗（40103）时按响应中的服务端时间校准时钟偏差 */
    private boolean syncClock(byte[] body) {
        try {
            var env = Json.parseObject(new String(body, StandardCharsets.UTF_8));
            long ts = Json.num(env, "timestamp");
            if (Json.num(env, "code") != ErrorCodes.TIMESTAMP_EXPIRED || ts <= 0) {
                return false;
            }
            skewMillis.set(ts - clock.getAsLong());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static Object decode(int status, byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        Map<String, Object> env;
        try {
            env = Json.parseObject(text);
        } catch (IllegalArgumentException e) {
            throw new HiFlowException(status, 0, truncate(text.strip(), 256), "");
        }
        long code = Json.num(env, "code");
        if (status >= 400 || code != 0) {
            throw new HiFlowException(status, (int) code, Json.str(env, "message"), Json.str(env, "request_id"));
        }
        return env.get("data");
    }

    static Map<String, Object> object(Object data) {
        return Json.asObject(data);
    }

    private static void putIfPresent(Map<String, Object> m, String k, String v) {
        if (v != null && !v.isEmpty()) {
            m.put(k, v);
        }
    }

    private static String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new InterruptedIOException("hiflow: interrupted"));
        }
    }

    public static final class Builder {
        private String baseUrl;
        private String clientId;
        private String clientSecret;
        private HttpClient httpClient;
        private String userAgent = "hiflow-java/" + VERSION;
        private int retries = 2;
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Duration backoff = Duration.ofMillis(200);

        /** 服务地址，可带反向代理前缀（如 https://example.com/api，代理转发时需去掉前缀，签名只覆盖 /open/v1/...） */
        public Builder baseUrl(String v) { baseUrl = v; return this; }

        public Builder credentials(String id, String secret) { clientId = id; clientSecret = secret; return this; }

        /** 自定义 HttpClient（代理、TLS、执行器等）；默认 HTTP/1.1、连接超时 10 秒 */
        public Builder httpClient(HttpClient v) { httpClient = v; return this; }

        public Builder userAgent(String v) { userAgent = v; return this; }

        /** 查询请求（GET）的重试次数，默认 2；0 表示不重试。发起、完成等写操作不自动重试，避免重复执行 */
        public Builder retries(int v) { retries = Math.max(v, 0); return this; }

        /** 单次请求超时，默认 30 秒；null 表示不限制 */
        public Builder requestTimeout(Duration v) { requestTimeout = v; return this; }

        Builder backoff(Duration v) { backoff = v; return this; }

        public HiFlowClient build() {
            if (baseUrl == null) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            var u = URI.create(baseUrl);
            if (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme()) || u.getHost() == null) {
                throw new IllegalArgumentException("invalid baseUrl: " + baseUrl);
            }
            if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty()) {
                throw new IllegalArgumentException("client id and secret are required");
            }
            baseUrl = baseUrl.replaceAll("/+$", "");
            return new HiFlowClient(this);
        }
    }
}
