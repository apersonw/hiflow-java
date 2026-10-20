package io.github.apersonw.hiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 与服务端 pkg/signer/signer_test.go、Go SDK signer_test.go 共用的签名向量 */
class SignerTest {
    @Test
    void sharedVector() {
        var body = "{\"operator\":\"alice\",\"variables\":{\"approved\":true}}".getBytes(StandardCharsets.UTF_8);
        var hash = Signer.sha256Hex(body);
        assertEquals("e1bdad04d8c6bc180c60bef1d8454510a154a9d755a28de93d50dcc6b5c6536c", hash);
        var query = new LinkedHashMap<String, List<String>>();
        query.put("b", List.of("2", "1"));
        query.put("a b", List.of("x y~"));
        var sts = Signer.stringToSign("post", "/open/v1/tasks/42/complete", query, "hf_test", "1700000000",
                "0123456789abcdef0123456789abcdef", hash);
        assertEquals("POST\n/open/v1/tasks/42/complete\na%20b=x%20y~&b=1&b=2\nhf_test\n1700000000\n0123456789abcdef0123456789abcdef\n" + hash, sts);
        assertEquals("NQQ/+k+GIQ+iNJdFDfLY1hRJjE87RyPSTYGF7GgtSlo=", Signer.sign("test-secret", sts));
    }

    @Test
    void escapeMatchesGo() {
        assertEquals("%E5%BC%A0%20%E4%B8%89%2B%2F", Signer.escape("张 三+/"));
    }
}
