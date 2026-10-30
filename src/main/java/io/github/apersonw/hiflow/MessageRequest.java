package io.github.apersonw.hiflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 发送消息：按实例 ID 或业务主键（如快递单号）找到等待该消息的实例。
 *
 * <pre>{@code
 * MessageRequest.byBusinessKey("SF1234567890", "delivered").messageId(pushId).ttl(Duration.ofDays(3)).variable("signer", "本人").build()
 * }</pre>
 */
public final class MessageRequest {
    private final Map<String, Object> body;

    private MessageRequest(Map<String, Object> body) {
        this.body = body;
    }

    /** 按实例 ID 发送 */
    public static Builder byInstance(long instanceId, String message) {
        return new Builder(message).put("instance_id", instanceId);
    }

    /** 按消息启动流程：发起开始节点以该消息启动的流程（有多个时用 {@link Builder#key} 限定），可再用 businessKey 设置业务主键 */
    public static Builder start(String message) {
        return new Builder(message);
    }

    /** 按业务主键发送（同一业务主键有多个流程的实例时用 {@link Builder#key} 限定流程）；没有实例在等待时，以该消息启动的流程会发起新实例 */
    public static Builder byBusinessKey(String businessKey, String message) {
        return new Builder(message).put("business_key", Objects.requireNonNull(businessKey, "businessKey"));
    }

    boolean idempotent() {
        return body.get("message_id") instanceof String s && !s.isEmpty();
    }

    Map<String, Object> toBody() {
        return body;
    }

    public static final class Builder {
        private final Map<String, Object> body = new LinkedHashMap<>();
        private final Map<String, Object> variables = new LinkedHashMap<>();

        private Builder(String message) {
            body.put("message", Objects.requireNonNull(message, "message"));
        }

        private Builder put(String k, Object v) {
            body.put(k, v);
            return this;
        }

        /** 限定流程标识 */
        public Builder key(String v) { return put("key", v); }

        /** 消息 ID：同一客户端内相同 ID 只处理一次 */
        public Builder messageId(String v) { return put("message_id", v); }

        /** 暂时没有等待该消息的节点时缓存多久（最长 30 天） */
        public Builder ttl(java.time.Duration v) { return put("ttl", v.toSeconds()); }

        /** 记为接收任务的处理人，默认 system */
        public Builder operator(String v) { return put("operator", v); }

        public Builder variable(String name, Object value) {
            variables.put(name, value);
            return this;
        }

        public Builder variables(Map<String, ?> v) {
            variables.putAll(v);
            return this;
        }

        public MessageRequest build() {
            var b = new LinkedHashMap<>(body);
            if (!variables.isEmpty()) {
                b.put("variables", new LinkedHashMap<>(variables));
            }
            return new MessageRequest(b);
        }
    }
}
