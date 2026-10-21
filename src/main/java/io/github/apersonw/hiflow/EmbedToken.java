package io.github.apersonw.hiflow;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 待办嵌入令牌：业务前端（浏览器 SDK / 嵌入组件）凭它以 {@code user} 的身份查看与处理他的待办，不需要客户端密钥。
 * 用 {@link HiFlowClient#createEmbedToken(Options)} 在本地签发（不请求 HiFlow）。
 *
 * <pre>
 * payload = base64url(JSON)
 * token   = payload + "." + base64url(HMAC-SHA256(client_secret, "hiflow-embed-token/v1\n" + payload))
 * </pre>
 *
 * @param token     交给业务前端的令牌
 * @param expiresAt 过期时间
 */
public record EmbedToken(String token, Instant expiresAt) {
    public static final String COMPLETE = "complete";
    public static final String CLAIM = "claim";
    public static final String TRANSFER = "transfer";
    public static final String RETURN = "return";
    /** 最长有效期 */
    public static final Duration MAX_TTL = Duration.ofHours(24);

    private static final String PREFIX = "hiflow-embed-token/v1\n";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 签发选项：user 必填；有效期默认 2 小时 */
    public static final class Options {
        private final String user;
        private Duration expiresIn = Duration.ofHours(2);
        private final List<String> actions = new ArrayList<>();
        private final List<String> variables = new ArrayList<>();
        private boolean readVariables;

        public Options(String user) {
            this.user = Objects.requireNonNull(user, "user");
        }

        public Options expiresIn(Duration d) {
            this.expiresIn = Objects.requireNonNull(d, "expiresIn");
            return this;
        }

        /** 允许的操作（{@link #COMPLETE} 等），不设置表示全部 */
        public Options actions(String... a) {
            actions.addAll(List.of(a));
            return this;
        }

        /** 处理任务时允许提交的流程变量名，不设置时只允许 approved；"*" 表示不限 */
        public Options variables(String... v) {
            variables.addAll(List.of(v));
            return this;
        }

        /** 任务详情中返回流程变量 */
        public Options readVariables(boolean b) {
            readVariables = b;
            return this;
        }
    }

    static EmbedToken sign(String clientId, String secret, Options o, long nowMillis) {
        if (o.user.isEmpty() || o.user.length() > 64) {
            throw new IllegalArgumentException("hiflow: embed token user must be 1-64 characters");
        }
        if (o.expiresIn.isNegative() || o.expiresIn.isZero() || o.expiresIn.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("hiflow: embed token ttl must be within 24h");
        }
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        long exp = (nowMillis + o.expiresIn.toMillis()) / 1000;
        var claims = new LinkedHashMap<String, Object>();
        claims.put("client_id", clientId);
        claims.put("user", o.user);
        claims.put("nonce", HexFormat.of().formatHex(nonce));
        claims.put("expires_at", exp);
        if (!o.actions.isEmpty()) {
            claims.put("actions", List.copyOf(o.actions));
        }
        if (!o.variables.isEmpty()) {
            claims.put("variables", List.copyOf(o.variables));
        }
        if (o.readVariables) {
            claims.put("read_variables", true);
        }
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        return new EmbedToken(fromPayload(secret, payload), Instant.ofEpochSecond(exp));
    }

    static String fromPayload(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(PREFIX.getBytes(StandardCharsets.UTF_8));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
