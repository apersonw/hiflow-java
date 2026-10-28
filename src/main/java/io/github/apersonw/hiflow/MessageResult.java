package io.github.apersonw.hiflow;

import java.util.Map;

/**
 * 发送消息的结果。
 *
 * @param status   {@link #DELIVERED} 已投递、{@link #BUFFERED} 已缓存等待投递、{@link #DUPLICATE} 同一 message_id 已处理过
 * @param instance 投递后的实例详情（只在 delivered 时返回，否则为 null）
 */
public record MessageResult(String status, long instanceId, long taskId, String messageId, Instance instance) {
    public static final String DELIVERED = "delivered";
    public static final String BUFFERED = "buffered";
    public static final String DUPLICATE = "duplicate";

    @SuppressWarnings("unchecked")
    static MessageResult from(Map<String, Object> m) {
        var in = m.get("instance") instanceof Map<?, ?> x ? Instance.from((Map<String, Object>) x) : null;
        return new MessageResult(Json.str(m, "status"), Json.num(m, "instance_id"), Json.num(m, "task_id"), Json.str(m, "message_id"), in);
    }
}
