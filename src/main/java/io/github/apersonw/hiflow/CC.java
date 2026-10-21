package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 抄送记录（只读，不影响流程推进）。
 *
 * @param taskId        触发抄送的任务，没有时为 null
 * @param user          被抄送人
 * @param sender        触发抄送的处理人，comment 为其处理意见
 * @param readAt        已读时间，未读为 null
 * @param instanceTitle 以下实例摘要字段在抄送列表中返回，实例详情中为空串
 */
public record CC(long id, long instanceId, Long taskId, String nodeId, String user, String sender, String comment,
                 OffsetDateTime readAt, OffsetDateTime createdAt, String instanceTitle, String definitionKey,
                 String definitionName, String businessKey, String starter, String taskName) {
    public boolean read() {
        return readAt != null;
    }

    static CC from(Map<String, Object> m) {
        return new CC(Json.num(m, "id"), Json.num(m, "instance_id"),
                m.get("task_id") instanceof Number n ? n.longValue() : null, Json.str(m, "node_id"),
                Json.str(m, "user"), Json.str(m, "sender"), Json.str(m, "comment"), Models.time(m, "read_at"),
                Models.time(m, "created_at"), Json.str(m, "instance_title"), Json.str(m, "definition_key"),
                Json.str(m, "definition_name"), Json.str(m, "business_key"), Json.str(m, "starter"), Json.str(m, "task_name"));
    }
}
