package io.github.apersonw.hiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiFlowClientTest {
    static final String ID = "hf_test";
    static final String SECRET = "test-secret";

    HttpServer server;
    /** 测试注册的处理函数：(路径, 请求体) → data；抛出 HiFlowException 表示返回错误 */
    volatile BiFunction<HttpExchange, String, Object> handler;
    final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serve);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    HiFlowClient client() {
        // 带反向代理前缀：前缀不参与签名
        return HiFlowClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/")
                .credentials(ID, SECRET).backoff(Duration.ofMillis(1)).build();
    }

    private void serve(HttpExchange ex) throws IOException {
        calls.incrementAndGet();
        var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var h = ex.getRequestHeaders();
        var path = ex.getRequestURI().getPath().replaceFirst("^/api", "");
        var hash = Signer.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
        var sts = Signer.stringToSign(ex.getRequestMethod(), path, query(ex.getRequestURI().getRawQuery()), h.getFirst("X-Client-Id"),
                h.getFirst("X-Timestamp"), h.getFirst("X-Nonce"), h.getFirst("X-Content-SHA256"));
        Object resp;
        int status = 200;
        if (!hash.equals(h.getFirst("X-Content-SHA256")) || !Signer.sign(SECRET, sts).equals(h.getFirst("X-Signature"))) {
            status = 401;
            resp = Map.of("code", ErrorCodes.SIGNATURE, "message", "签名校验失败");
        } else {
            try {
                resp = Map.of("code", 0, "message", "ok", "data", handler.apply(ex, body));
            } catch (HiFlowException e) {
                status = e.getStatusCode();
                resp = Map.of("code", e.getCode(), "message", "err", "request_id", "rid");
            }
        }
        var out = Json.write(resp).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    static Map<String, List<String>> query(String raw) {
        var q = new LinkedHashMap<String, List<String>>();
        if (raw == null || raw.isEmpty()) {
            return q;
        }
        for (var kv : raw.split("&")) {
            var i = kv.indexOf('=');
            var k = URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8);
            var v = URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            q.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return q;
    }

    @Test
    void startAndComplete() {
        handler = (ex, body) -> {
            var path = ex.getRequestURI().getPath();
            if (path.equals("/api/open/v1/instances")) {
                var req = Json.parseObject(body);
                assertEquals("leave", req.get("key"));
                assertEquals(3L, Json.asObject(req.get("variables")).get("days"));
                return Map.of("id", 7, "status", "running", "started_at", "2026-10-20T06:00:00Z",
                        "tasks", List.of(Map.of("id", 11, "status", "active", "assignee", "u7", "candidates", List.of())));
            }
            assertEquals("/api/open/v1/tasks/11/complete", path);
            var req = Json.parseObject(body);
            assertEquals("u7", req.get("operator"));
            assertEquals(Boolean.TRUE, Json.asObject(req.get("variables")).get("approved"));
            return Map.of("id", 7, "status", "completed");
        };
        var c = client();
        var in = c.start(StartRequest.builder("leave").starter("u1").variable("days", 3).build());
        assertTrue(in.running());
        assertEquals(1, in.activeTasks().size());
        var done = c.complete(in.activeTasks().get(0).id(), "u7", "同意", Map.of("approved", true));
        assertEquals(Instance.COMPLETED, done.status());
    }

    @Test
    void todoQueryAndUnicodePath() {
        handler = (ex, body) -> {
            if (ex.getRequestURI().getPath().startsWith("/api/open/v1/definitions/")) {
                return Map.of("id", 1, "key", "请假", "version", 2);
            }
            var q = query(ex.getRequestURI().getRawQuery());
            assertEquals(List.of("张 三"), q.get("user"));
            assertEquals(List.of("active"), q.get("status"));
            return Map.of("list", List.of(Map.of("id", 1, "name", "经理审批")), "total", 1, "page", 1, "page_size", 100);
        };
        var page = client().todo("张 三");
        assertEquals(1, page.total());
        assertEquals("经理审批", page.list().get(0).name());
        assertEquals(2, client().definition("请假").version());
    }

    @Test
    void errorAndRetryPolicy() {
        handler = (ex, body) -> {
            throw new HiFlowException(503, ErrorCodes.DEPENDENCY, "", "");
        };
        var c = client();
        var e = assertThrows(HiFlowException.class, () -> c.task(1));
        assertEquals(ErrorCodes.DEPENDENCY, e.getCode());
        assertEquals("rid", e.getRequestId());
        assertEquals(3, calls.get(), "GET 重试 2 次");

        calls.set(0);
        assertThrows(HiFlowException.class, () -> c.start(StartRequest.builder("k").build()));
        assertEquals(1, calls.get(), "写操作不重试");

        handler = (ex, body) -> {
            throw new HiFlowException(404, ErrorCodes.NOT_FOUND, "", "");
        };
        assertTrue(assertThrows(HiFlowException.class, () -> c.instance(9)).isNotFound());
    }

    @Test
    void returnAndNewTaskFields() {
        handler = (ex, body) -> {
            var path = ex.getRequestURI().getPath();
            if (path.endsWith("/return-targets")) {
                return List.of(Map.of("node_id", "submit", "name", "提交", "operator", "u1", "completed_at", "2026-10-20T06:00:00Z"));
            }
            assertEquals("/api/open/v1/tasks/9/return", path);
            var req = Json.parseObject(body);
            assertEquals("submit", req.get("target_node"));
            return Map.of("id", 1, "status", "running", "tasks", List.of(Map.of(
                    "id", 10, "node_id", "submit", "status", "active", "scope", "/F.1.0", "multi_key", "k",
                    "approved", false, "due_at", "2026-10-21T06:00:00Z", "overdue", true, "candidates", List.of())));
        };
        var c = client();
        var targets = c.returnTargets(9);
        assertEquals("u1", targets.get(0).operator());
        var t = c.returnTask(9, "s1", "submit", "补充").tasks().get(0);
        assertEquals("/F.1.0", t.scope());
        assertEquals(Boolean.FALSE, t.approved());
        assertTrue(t.overdue());
        assertEquals(2026, t.dueAt().getYear());
    }

    @Test
    void builderValidation() {
        assertThrows(IllegalArgumentException.class, () -> HiFlowClient.builder().baseUrl("ftp://x").credentials(ID, SECRET).build());
        assertThrows(IllegalArgumentException.class, () -> HiFlowClient.builder().baseUrl("http://x").credentials("", SECRET).build());
    }
}
