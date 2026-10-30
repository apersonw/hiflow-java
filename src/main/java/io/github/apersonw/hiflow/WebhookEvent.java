package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * HiFlow 推送的事件。同一事件可能重复推送（至少投递一次），请按 {@link #id()} 去重；不保证顺序，
 * 需要最新状态时按 {@code instance().id()} 查询实例详情。
 *
 * @param instance 实例摘要（不含流程变量），流程事件总是有
 * @param task     任务，在 task.* 与 cc.created 事件中有，否则为 null
 * @param cc       抄送，只在 cc.created 事件中有，否则为 null
 * @param operator 触发事件的操作者
 * @param data     原始数据（webhook.test 等非流程事件使用）
 */
public record WebhookEvent(String id, String type, OffsetDateTime createdAt, EventInstance instance, EventTask task, EventCC cc,
                           String operator, String comment, Map<String, Object> data) {
    public static final String INSTANCE_STARTED = "instance.started";
    public static final String INSTANCE_COMPLETED = "instance.completed";
    public static final String INSTANCE_CANCELED = "instance.canceled";
    /** 实例被挂起（comment 为原因） */
    public static final String INSTANCE_SUSPENDED = "instance.suspended";
    /** 挂起的实例被恢复 */
    public static final String INSTANCE_RESUMED = "instance.resumed";
    public static final String TASK_CREATED = "task.created";
    public static final String TASK_COMPLETED = "task.completed";
    public static final String TASK_CANCELED = "task.canceled";
    /** 任务的处理人变化（认领、转办），task().assignee() 为新处理人 */
    public static final String TASK_ASSIGNED = "task.assigned";
    /** 任务被退回，目标节点随后产生 task.created */
    public static final String TASK_RETURNED = "task.returned";
    /** 待办超时（节点配置 timeout.action = remind，每个待办一次） */
    public static final String TASK_OVERDUE = "task.overdue";
    /** 服务任务调用失败且重试已用尽（comment() 为失败原因），可在管理后台重试或代为完成 */
    public static final String TASK_FAILED = "task.failed";
    /** 产生抄送记录（每位被抄送人一个），cc() 为抄送信息，task() 为触发抄送的任务 */
    public static final String CC_CREATED = "cc.created";
    /** 待办的处理人在组织架构中已停用或不存在（每个待办一次，之后按目录配置交接） */
    public static final String TASK_ASSIGNEE_INACTIVE = "task.assignee_inactive";
    /** 发起人催办（每个被催办的待办一条，comment 为催办说明），由业务系统通知处理人 */
    public static final String TASK_URGED = "task.urged";
    /** 处理人规则解析回调（发往客户端的回调地址，需同步响应），见 {@link ResolveRequest} */
    public static final String RESOLVE_USERS = "resolve.users";
    /** 服务任务调用回调（发往客户端的回调地址，需同步响应），见 {@link ServiceRequest} */
    public static final String SERVICE_INVOKE = "service.invoke";
    /** 管理后台“测试回调地址”发送的探测请求（发往客户端的回调地址），校验签名后返回 2xx 即视为连通 */
    public static final String CALLBACK_TEST = "callback.test";
    public static final String WEBHOOK_TEST = "webhook.test";

    static WebhookEvent from(Map<String, Object> m) {
        var data = Models.vars(m, "data");
        var in = Json.obj(data, "instance");
        var t = Json.obj(data, "task");
        var cc = Json.obj(data, "cc");
        return new WebhookEvent(Json.str(m, "id"), Json.str(m, "type"), Models.time(m, "created_at"),
                in == null ? null : EventInstance.from(in), t == null ? null : EventTask.from(t),
                cc == null ? null : EventCC.from(cc),
                Json.str(data, "operator"), Json.str(data, "comment"), data);
    }

    /** 事件中的实例摘要 */
    public record EventInstance(long id, String definitionKey, int definitionVersion, String definitionName, String businessKey,
                                String title, String starter, String status, OffsetDateTime startedAt, OffsetDateTime endedAt,
                                String endReason, String result) {
        static EventInstance from(Map<String, Object> m) {
            return new EventInstance(Json.num(m, "id"), Json.str(m, "definition_key"), (int) Json.num(m, "definition_version"),
                    Json.str(m, "definition_name"), Json.str(m, "business_key"), Json.str(m, "title"), Json.str(m, "starter"),
                    Json.str(m, "status"), Models.time(m, "started_at"), Models.time(m, "ended_at"), Json.str(m, "end_reason"),
                    Json.str(m, "result"));
        }
    }

    /** 事件中的抄送 */
    public record EventCC(long id, String user, String sender, String nodeId) {
        static EventCC from(Map<String, Object> m) {
            return new EventCC(Json.num(m, "id"), Json.str(m, "user"), Json.str(m, "sender"), Json.str(m, "node_id"));
        }
    }

    /** 事件中的任务 */
    public record EventTask(long id, String nodeId, String name, String kind, String assignee, String delegatedFrom, List<String> candidates, String status,
                            String operator, String comment, OffsetDateTime createdAt, OffsetDateTime completedAt) {
        static EventTask from(Map<String, Object> m) {
            return new EventTask(Json.num(m, "id"), Json.str(m, "node_id"), Json.str(m, "name"), Json.str(m, "kind"),
                    Json.str(m, "assignee"), Json.str(m, "delegated_from"), Models.strings(m, "candidates"), Json.str(m, "status"), Json.str(m, "operator"), Json.str(m, "comment"),
                    Models.time(m, "created_at"), Models.time(m, "completed_at"));
        }
    }
}
