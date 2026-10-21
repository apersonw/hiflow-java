package io.github.apersonw.hiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class EmbedTokenTest {
    /** 与服务端 pkg/embedtoken、Go SDK、浏览器 SDK 共用的签名向量 */
    @Test
    void sharedVector() {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"client_id\":\"hf_test\",\"user\":\"u1\",\"nonce\":\"0123456789abcdef\",\"expires_at\":1700000000}".getBytes(StandardCharsets.UTF_8));
        assertEquals("eyJjbGllbnRfaWQiOiJoZl90ZXN0IiwidXNlciI6InUxIiwibm9uY2UiOiIwMTIzNDU2Nzg5YWJjZGVmIiwiZXhwaXJlc19hdCI6MTcwMDAwMDAwMH0.fYN-4Mukn5H-7ZT23S4QVOeVjXal60YCqRDivVPefbg", EmbedToken.fromPayload("sk_test_embed", payload));
    }

    @Test
    void signsClaims() {
        var t = EmbedToken.sign("hf_x", "sk", new EmbedToken.Options("u1").expiresIn(Duration.ofHours(1)).actions(EmbedToken.COMPLETE), 1_700_000_000_000L);
        assertEquals(1_700_003_600L, t.expiresAt().getEpochSecond());
        String payload = t.token().substring(0, t.token().indexOf('.'));
        String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        var m = Json.parseObject(json);
        assertEquals("u1", m.get("user"));
        assertEquals("hf_x", m.get("client_id"));
        assertEquals(32, ((String) m.get("nonce")).length());
        assertTrue(json.contains("\"actions\":[\"complete\"]"));
        assertEquals(t.token(), EmbedToken.fromPayload("sk", payload));
        assertThrows(IllegalArgumentException.class, () -> EmbedToken.sign("hf_x", "sk", new EmbedToken.Options("u").expiresIn(Duration.ofHours(48)), 0));
        assertThrows(IllegalArgumentException.class, () -> EmbedToken.sign("hf_x", "sk", new EmbedToken.Options(""), 0));
    }
}
