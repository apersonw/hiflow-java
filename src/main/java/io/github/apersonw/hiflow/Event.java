package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 流程轨迹。
 *
 * @param type   instance_started | node_entered | task_created | task_completed | task_canceled | task_transferred
 *               | instance_completed | instance_canceled
 * @param taskId 与任务相关的事件为任务 ID，否则为 null
 */
public record Event(long id, String type, Long taskId, String nodeId, String operator, String comment, OffsetDateTime createdAt) {
    static Event from(Map<String, Object> m) {
        return new Event(Json.num(m, "id"), Json.str(m, "type"), Json.optNum(m, "task_id"), Json.str(m, "node_id"),
                Json.str(m, "operator"), Json.str(m, "comment"), Models.time(m, "created_at"));
    }
}
