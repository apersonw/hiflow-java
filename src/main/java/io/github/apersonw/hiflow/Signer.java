package io.github.apersonw.hiflow;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 与服务端一致的请求签名：
 *
 * <pre>
 * StringToSign = Method + "\n" + Path + "\n" + CanonicalQuery + "\n"
 *              + ClientId + "\n" + Timestamp + "\n" + Nonce + "\n" + ContentSHA256
 * Signature    = Base64(HMAC-SHA256(ClientSecret, StringToSign))
 * </pre>
 *
 * Path 为服务端看到的路径（/open/v1/...，不含反向代理前缀与查询串）；CanonicalQuery 按参数名、参数值升序，
 * 名与值按 RFC 3986 编码（只保留 A-Z a-z 0-9 - _ . ~，空格为 %20）后以 &amp; 连接；
 * ContentSHA256 为请求体 SHA-256 小写十六进制。
 */
final class Signer {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final HexFormat HEX_UPPER = HexFormat.of().withUpperCase();

    /** 空请求体的 SHA-256（须在 HEX 之后初始化） */
    static final String EMPTY_SHA256 = sha256Hex(new byte[0]);

    private Signer() {}

    static String stringToSign(String method, String path, Map<String, List<String>> query, String clientId,
                               String timestamp, String nonce, String contentSha256) {
        return String.join("\n", method.toUpperCase(Locale.ROOT), path, canonicalQuery(query),
                clientId, timestamp, nonce, contentSha256);
    }

    static String sign(String secret, String stringToSign) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String canonicalQuery(Map<String, List<String>> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        for (var e : new TreeMap<>(query).entrySet()) {
            var values = new ArrayList<>(e.getValue());
            values.sort(null);
            for (var v : values) {
                if (!sb.isEmpty()) {
                    sb.append('&');
                }
                sb.append(escape(e.getKey())).append('=').append(escape(v));
            }
        }
        return sb.toString();
    }

    /** RFC 3986 编码：与 Go url.QueryEscape 后把 + 换成 %20 的结果一致（大写十六进制）。 */
    static String escape(String s) {
        var sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX_UPPER.toHexDigits((byte) c));
            }
        }
        return sb.toString();
    }

    static String sha256Hex(byte[] data) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String newNonce() {
        var b = new byte[16];
        RANDOM.nextBytes(b);
        return HEX.formatHex(b);
    }
}
