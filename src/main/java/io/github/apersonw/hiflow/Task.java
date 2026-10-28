package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 人工任务。
 *
 * @param kind          user 人工任务 | service 服务任务（引擎回调业务系统执行） | receive 接收任务（等待 {@link HiFlowClient#signal}） | timer 定时器 | subprocess 子流程
 * @param assignee      处理人；为空时 {@code candidates} 中任一人可认领或直接处理
 * @param status        active | completed | canceled | waiting（依次会签中尚未轮到）
 * @param scope         所在并行分支作用域（根为空串）
 * @param multiKey      多实例（会签）批次，同一节点同一次进入的待办相同；普通任务为空串
 * @param approved      多实例任务的本人表态，未表态为 null
 * @param delegatedFrom 按委托规则交给代理人（assignee）时的原处理人，否则为空串；委托人本人也可处理
 * @param dueAt         超时时间，未配置为 null
 * @param overdue       进行中且已超时（任务列表与详情中返回）
 * @param attempts      服务任务已调用次数
 * @param nextRunAt     服务任务下次调用时间，进行中且为 null 表示重试已用尽
 * @param lastError     服务任务最近一次调用失败的原因
 * @param childInstanceId 子流程任务发起的子实例，否则为 null
 * @param resumeNode    退回时选择了“处理后直接回到退回人”：本待办处理后回到的节点，否则为空串
 * @param instanceTitle 以下实例摘要字段在任务列表与任务详情中返回，实例详情中的任务为空串
 * @param form          本节点的表单视图（只在任务详情 {@link HiFlowClient#task} 中返回；模型没有定义表单时为 null）
 */
public record Task(long id, long instanceId, String nodeId, String name, String kind, String assignee, List<String> candidates,
                   String status, String operator, String comment, String scope, String multiKey, Boolean approved, String delegatedFrom,
                   OffsetDateTime dueAt, boolean overdue, int attempts, OffsetDateTime nextRunAt, String lastError, Long childInstanceId, String resumeNode, OffsetDateTime createdAt, OffsetDateTime completedAt,
                   String instanceTitle, String definitionKey, String definitionName, String businessKey, String starter, FormView form) {
    public static final String ACTIVE = "active";
    public static final String COMPLETED = "completed";
    public static final String CANCELED = "canceled";
    public static final String WAITING = "waiting";
    public static final String KIND_USER = "user";
    public static final String KIND_SERVICE = "service";
    public static final String KIND_RECEIVE = "receive";
    /** 定时器：nextRunAt 到期后自动推进 */
    public static final String KIND_TIMER = "timer";
    /** 子流程：childInstanceId 结束后自动推进 */
    public static final String KIND_SUBPROCESS = "subprocess";

    public boolean active() {
        return ACTIVE.equals(status);
    }

    static Task from(Map<String, Object> m) {
        return new Task(Json.num(m, "id"), Json.num(m, "instance_id"), Json.str(m, "node_id"), Json.str(m, "name"),
                Json.str(m, "kind"), Json.str(m, "assignee"), Models.strings(m, "candidates"), Json.str(m, "status"), Json.str(m, "operator"),
                Json.str(m, "comment"), Json.str(m, "scope"), Json.str(m, "multi_key"),
                m.get("approved") instanceof Boolean b ? b : null, Json.str(m, "delegated_from"), Models.time(m, "due_at"), Json.bool(m, "overdue"),
                (int) Json.num(m, "attempts"), Models.time(m, "next_run_at"), Json.str(m, "last_error"),
                Models.optLong(m, "child_instance_id"), Json.str(m, "resume_node"),
                Models.time(m, "created_at"), Models.time(m, "completed_at"),
                Json.str(m, "instance_title"), Json.str(m, "definition_key"), Json.str(m, "definition_name"),
                Json.str(m, "business_key"), Json.str(m, "starter"), FormView.from(Json.obj(m, "form")));
    }
}
