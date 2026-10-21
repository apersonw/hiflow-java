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
import java.time.OffsetDateTime;
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
    public static final String VERSION = "0.1.2";

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

    /**
     * 向实例发送消息：完成等待该消息（节点 message）的接收任务，variables 合并到流程变量后继续推进；
     * 没有等待该消息的接收任务时抛出 {@link ErrorCodes#CONFLICT}。
     *
     * @param operator 记为接收任务的处理人，null 时为 system
     */
    public Instance signal(long instanceId, String message, Map<String, ?> variables, String operator) {
        var body = new LinkedHashMap<String, Object>();
        body.put("message", Objects.requireNonNull(message, "message"));
        putIfPresent(body, "operator", operator);
        if (variables != null && !variables.isEmpty()) {
            body.put("variables", variables);
        }
        return Instance.from(object(call("POST", "/open/v1/instances/" + instanceId + "/signal", Map.of(), body)));
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
        return complete(taskId, operator, comment, variables, null);
    }

    /**
     * 完成任务并抄送：cc 中的用户收到抄送记录（另有节点配置的抄送在节点处理完成时产生）；其余同
     * {@link #complete(long, String, String, Map)}。
     */
    public Instance complete(long taskId, String operator, String comment, Map<String, ?> variables, List<String> cc) {
        var body = new LinkedHashMap<String, Object>();
        body.put("operator", Objects.requireNonNull(operator, "operator"));
        putIfPresent(body, "comment", comment);
        if (variables != null && !variables.isEmpty()) {
            body.put("variables", variables);
        }
        if (cc != null && !cc.isEmpty()) {
            body.put("cc", cc);
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

    /**
     * 加签：会签（多实例）处理中临时加人，新待办加入同一批次（依次处理时排在最后），完成条件按新的总人数计算。
     * 非多实例任务、或加签的人都已在会签中时抛出 {@link ErrorCodes#BAD_REQUEST}。
     */
    public Instance addSigners(long taskId, String operator, List<String> users, String comment) {
        return Instance.from(object(call("POST", "/open/v1/tasks/" + taskId + "/add-signers", Map.of(), signers(operator, users, comment))));
    }

    /** 减签：取消会签中尚未处理的人（不能减掉自己），剩余的人已满足完成条件时节点随即结束并推进 */
    public Instance removeSigners(long taskId, String operator, List<String> users, String comment) {
        return Instance.from(object(call("POST", "/open/v1/tasks/" + taskId + "/remove-signers", Map.of(), signers(operator, users, comment))));
    }

    private static Map<String, Object> signers(String operator, List<String> users, String comment) {
        var body = new LinkedHashMap<String, Object>();
        body.put("operator", Objects.requireNonNull(operator, "operator"));
        body.put("users", List.copyOf(Objects.requireNonNull(users, "users")));
        putIfPresent(body, "comment", comment);
        return body;
    }

    // ---------- 委托代理 ----------

    /**
     * 设置委托代理：生效期内 user 的新待办（指定处理人与会签处理人）改由 delegate 处理，委托人本人仍可处理；
     * 已生成的待办不变，候选人不受影响。
     *
     * @param definitionKey 只委托该流程的待办，null 表示全部流程
     * @param startsAt      开始时间，null 表示现在
     * @param endsAt        结束时间（不含），null 表示长期有效
     */
    public Delegation createDelegation(String user, String delegate, String definitionKey, OffsetDateTime startsAt,
                                       OffsetDateTime endsAt, String reason) {
        var body = new LinkedHashMap<String, Object>();
        body.put("user", Objects.requireNonNull(user, "user"));
        body.put("delegate", Objects.requireNonNull(delegate, "delegate"));
        putIfPresent(body, "definition_key", definitionKey);
        if (startsAt != null) {
            body.put("starts_at", startsAt.toString());
        }
        if (endsAt != null) {
            body.put("ends_at", endsAt.toString());
        }
        putIfPresent(body, "reason", reason);
        return Delegation.from(object(call("POST", "/open/v1/delegations", Map.of(), body)));
    }

    /**
     * 查询一页委托代理规则（最新的在前）。
     *
     * @param user       委托人，可为 null
     * @param delegate   代理人，可为 null
     * @param activeOnly 只看当前生效的
     */
    public Page<Delegation> delegations(String user, String delegate, boolean activeOnly, int page, int pageSize) {
        var q = new LinkedHashMap<String, List<String>>();
        if (user != null && !user.isEmpty()) {
            q.put("user", List.of(user));
        }
        if (delegate != null && !delegate.isEmpty()) {
            q.put("delegate", List.of(delegate));
        }
        if (activeOnly) {
            q.put("active", List.of("true"));
        }
        if (page > 0) {
            q.put("page", List.of(Integer.toString(page)));
        }
        if (pageSize > 0) {
            q.put("page_size", List.of(Integer.toString(pageSize)));
        }
        return Page.from(object(call("GET", "/open/v1/delegations", q, null)), Delegation::from);
    }

    /** 删除委托代理规则（立即失效，已交给代理人的待办不变） */
    public void deleteDelegation(long id) {
        call("DELETE", "/open/v1/delegations/" + id, Map.of(), null);
    }

    // ---------- 抄送 ----------

    /**
     * 查询一页抄送记录（最新的在前）。
     *
     * @param user       被抄送人（“抄送我的”），可为 null
     * @param unreadOnly 只看未读
     * @param page       页码，从 1 开始；0 为默认
     * @param pageSize   每页条数；0 为默认
     */
    public Page<CC> ccs(String user, boolean unreadOnly, int page, int pageSize) {
        var q = new LinkedHashMap<String, List<String>>();
        if (user != null && !user.isEmpty()) {
            q.put("user", List.of(user));
        }
        if (unreadOnly) {
            q.put("unread", List.of("true"));
        }
        if (page > 0) {
            q.put("page", List.of(Integer.toString(page)));
        }
        if (pageSize > 0) {
            q.put("page_size", List.of(Integer.toString(pageSize)));
        }
        return Page.from(object(call("GET", "/open/v1/cc", q, null)), CC::from);
    }

    /** 标记抄送已读；user 须为被抄送人 */
    public CC readCC(long id, String user) {
        return CC.from(object(call("POST", "/open/v1/cc/" + id + "/read", Map.of(), Map.of("user", Objects.requireNonNull(user, "user")))));
    }

    // ---------- 待办嵌入 ----------

    /**
     * 用客户端密钥在本地签发待办嵌入令牌（不请求 HiFlow），把 {@link EmbedToken#token()} 返回给业务前端，
     * 业务前端凭它以该用户的身份调用 /embed/v1（浏览器 SDK / 嵌入组件）。
     */
    public EmbedToken createEmbedToken(EmbedToken.Options options) {
        return EmbedToken.sign(clientId, clientSecret, Objects.requireNonNull(options, "options"), now());
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
