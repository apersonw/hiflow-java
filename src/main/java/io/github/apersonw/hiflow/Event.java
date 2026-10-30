package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 流程轨迹。
 *
 * @param type   instance_started | node_entered | task_created | task_completed | task_canceled | task_transferred
 *               | instance_completed | instance_canceled | instance_suspended | instance_resumed | node_jumped
 *               | variables_updated | business_key_changed | variables_changed（流程变量变化，data 为 {"changes": [{name, old, new, added, removed}]}）
 * @param taskId 与任务相关的事件为任务 ID，否则为 null
 * @param data   结构化数据（variables_changed 的变量前后值），其它事件为空 Map
 */
public record Event(long id, String type, Long taskId, String nodeId, String operator, String comment, OffsetDateTime createdAt,
                    Map<String, Object> data) {
    static Event from(Map<String, Object> m) {
        return new Event(Json.num(m, "id"), Json.str(m, "type"), Json.optNum(m, "task_id"), Json.str(m, "node_id"),
                Json.str(m, "operator"), Json.str(m, "comment"), Models.time(m, "created_at"), Models.vars(m, "data"));
    }
}
