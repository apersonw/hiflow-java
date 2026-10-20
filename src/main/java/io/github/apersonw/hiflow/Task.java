package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 人工任务。
 *
 * @param assignee      处理人；为空时 {@code candidates} 中任一人可认领或直接处理
 * @param status        active | completed | canceled | waiting（依次会签中尚未轮到）
 * @param scope         所在并行分支作用域（根为空串）
 * @param multiKey      多实例（会签）批次，同一节点同一次进入的待办相同；普通任务为空串
 * @param approved      多实例任务的本人表态，未表态为 null
 * @param dueAt         超时时间，未配置为 null
 * @param overdue       进行中且已超时（任务列表与详情中返回）
 * @param instanceTitle 以下实例摘要字段在任务列表与任务详情中返回，实例详情中的任务为空串
 */
public record Task(long id, long instanceId, String nodeId, String name, String assignee, List<String> candidates,
                   String status, String operator, String comment, String scope, String multiKey, Boolean approved,
                   OffsetDateTime dueAt, boolean overdue, OffsetDateTime createdAt, OffsetDateTime completedAt,
                   String instanceTitle, String definitionKey, String definitionName, String businessKey, String starter) {
    public static final String ACTIVE = "active";
    public static final String COMPLETED = "completed";
    public static final String CANCELED = "canceled";
    public static final String WAITING = "waiting";

    public boolean active() {
        return ACTIVE.equals(status);
    }

    static Task from(Map<String, Object> m) {
        return new Task(Json.num(m, "id"), Json.num(m, "instance_id"), Json.str(m, "node_id"), Json.str(m, "name"),
                Json.str(m, "assignee"), Models.strings(m, "candidates"), Json.str(m, "status"), Json.str(m, "operator"),
                Json.str(m, "comment"), Json.str(m, "scope"), Json.str(m, "multi_key"),
                m.get("approved") instanceof Boolean b ? b : null, Models.time(m, "due_at"), Json.bool(m, "overdue"),
                Models.time(m, "created_at"), Models.time(m, "completed_at"),
                Json.str(m, "instance_title"), Json.str(m, "definition_key"), Json.str(m, "definition_name"),
                Json.str(m, "business_key"), Json.str(m, "starter"));
    }
}
