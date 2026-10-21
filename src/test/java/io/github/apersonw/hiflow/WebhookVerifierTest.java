package io.github.apersonw.hiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class WebhookVerifierTest {
    static String sign(String secret, long ts, String body) {
        return "t=" + ts + ",v1=" + HexFormat.of().formatHex(WebhookVerifier.hmac(secret, ts, body.getBytes(StandardCharsets.UTF_8)));
    }

    /** 与服务端 internal/service/webhook_sign_test.go、Go SDK webhook_test.go 共用的签名向量 */
    @Test
    void sharedVector() {
        var body = "{\"id\":\"e1\",\"type\":\"webhook.test\",\"created_at\":\"2026-10-20T07:00:00Z\",\"data\":{}}";
        assertEquals("t=1700000000,v1=125598be773b84023832595d02264cbe20d926bab5c9f0e5c74bc164b1d8e226", sign("whsec_test", 1700000000, body));
        var e = WebhookVerifier.verify("whsec_test", sign("whsec_test", 1700000000, body), body.getBytes(StandardCharsets.UTF_8), Duration.ofDays(36500));
        assertEquals(WebhookEvent.WEBHOOK_TEST, e.type());
        assertNull(e.instance());
    }

    @Test
    void resolveCallback() {
        var body = """
                {"id":"c1","type":"resolve.users","created_at":"2026-10-20T07:00:00Z",
                 "data":{"rule":"leader","args":["u1"],"node_id":"mgr","instance":{"id":7,"starter":"u1"},"variables":{"amount":3}}}""";
        long now = Instant.now().getEpochSecond();
        var req = ResolveRequest.from(WebhookVerifier.verify("sec", sign("sec", now, body), body.getBytes(StandardCharsets.UTF_8)));
        assertEquals("leader", req.rule());
        assertEquals(java.util.List.of("u1"), req.args());
        assertEquals("u1", req.instance().starter());
        assertEquals(3L, ((Number) req.variables().get("amount")).longValue());
        assertEquals("{\"users\":[\"m1\"]}", ResolveRequest.response(java.util.List.of("m1")));
    }

    @Test
    void serviceCallback() {
        var body = """
                {"id":"c2","type":"service.invoke","created_at":"2026-10-20T07:00:00Z",
                 "data":{"service":"account.open","params":{"user":"u1"},"node_id":"svc","task_id":12,"attempt":2,"instance":{"id":7},"variables":{}}}""";
        long now = Instant.now().getEpochSecond();
        var req = ServiceRequest.from(WebhookVerifier.verify("sec", sign("sec", now, body), body.getBytes(StandardCharsets.UTF_8)));
        assertEquals("account.open", req.service());
        assertEquals("u1", req.params().get("user"));
        assertEquals(12, req.taskId());
        assertEquals(2, req.attempt());
        assertEquals(7, req.instance().id());
        assertEquals("{\"variables\":{\"ok\":true}}", ServiceRequest.response(java.util.Map.of("ok", true)));
        assertThrows(IllegalArgumentException.class, () -> ResolveRequest.from(WebhookVerifier.verify("sec", sign("sec", now, body), body.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void verifyAndParse() {
        var body = """
                {"id":"e1","type":"task.created","created_at":"2026-10-20T07:00:00Z",
                 "data":{"instance":{"id":7,"definition_key":"leave","business_key":"L-1","status":"running"},
                         "task":{"id":11,"node_id":"manager","name":"经理审批","assignee":"u7","candidates":["a"],"status":"active"}}}""";
        long now = Instant.now().getEpochSecond();
        var e = WebhookVerifier.verify("whsec_x", sign("whsec_x", now, body), body.getBytes(StandardCharsets.UTF_8));
        assertEquals("L-1", e.instance().businessKey());
        assertEquals("u7", e.task().assignee());
        assertEquals(java.util.List.of("a"), e.task().candidates());

        assertThrows(WebhookVerifier.WebhookSignatureException.class,
                () -> WebhookVerifier.verify("other", sign("whsec_x", now, body), body.getBytes(StandardCharsets.UTF_8)));
        assertThrows(WebhookVerifier.WebhookSignatureException.class,
                () -> WebhookVerifier.verify("whsec_x", sign("whsec_x", now - 3600, body), body.getBytes(StandardCharsets.UTF_8)));
        assertThrows(WebhookVerifier.WebhookSignatureException.class,
                () -> WebhookVerifier.verify("whsec_x", "garbage", body.getBytes(StandardCharsets.UTF_8)));
    }
}
