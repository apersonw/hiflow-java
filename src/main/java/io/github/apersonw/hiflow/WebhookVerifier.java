package io.github.apersonw.hiflow;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 校验 HiFlow Webhook 推送：请求头 {@value #SIGNATURE_HEADER} 为 {@code t={Unix 秒},v1={hex(HMAC-SHA256(secret, "{t}.{body}"))}}。
 *
 * <pre>{@code
 * WebhookEvent event = WebhookVerifier.verify(secret, request.getHeader(WebhookVerifier.SIGNATURE_HEADER), rawBody);
 * if (WebhookEvent.TASK_CREATED.equals(event.type())) { notify(event.task().assignee(), event.instance().title()); }
 * }</pre>
 *
 * body 须为原始请求体字节（未经框架重新序列化）。校验失败抛出 {@link WebhookSignatureException}，接收方应返回 401；
 * 处理失败时返回非 2xx，HiFlow 会按退避重试。
 */
public final class WebhookVerifier {
    public static final String SIGNATURE_HEADER = "X-HiFlow-Signature";
    /** 签名时间与本机时间允许的偏差 */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private WebhookVerifier() {}

    public static WebhookEvent verify(String secret, String signature, byte[] body) {
        return verify(secret, signature, body, DEFAULT_TOLERANCE);
    }

    public static WebhookEvent verify(String secret, String signature, byte[] body, Duration tolerance) {
        long ts = 0;
        var sigs = new ArrayList<byte[]>();
        for (var part : (signature == null ? "" : signature).split(",")) {
            var kv = part.strip().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            try {
                switch (kv[0]) {
                    case "t" -> ts = Long.parseLong(kv[1]);
                    case "v1" -> sigs.add(HexFormat.of().parseHex(kv[1]));
                    default -> { }
                }
            } catch (IllegalArgumentException ignored) {
                // 格式错误的片段忽略
            }
        }
        if (ts == 0 || sigs.isEmpty()) {
            throw new WebhookSignatureException("missing or malformed signature");
        }
        byte[] expected = hmac(secret, ts, body);
        boolean matched = false;
        for (var s : sigs) {
            matched = MessageDigest.isEqual(s, expected) | matched;
        }
        if (!matched) {
            throw new WebhookSignatureException("signature mismatch");
        }
        long age = Instant.now().getEpochSecond() - ts;
        if (Math.abs(age) > tolerance.toSeconds()) {
            throw new WebhookSignatureException("timestamp outside tolerance (" + age + "s)");
        }
        return WebhookEvent.from(Json.parseObject(new String(body, StandardCharsets.UTF_8)));
    }

    static byte[] hmac(String secret, long ts, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((ts + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 签名缺失、不匹配或超出允许的时间偏差 */
    public static final class WebhookSignatureException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public WebhookSignatureException(String message) {
            super("hiflow: invalid webhook: " + message);
        }
    }
}
