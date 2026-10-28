package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 流程实例。列表查询时 {@code tasks}、{@code events}、{@code cc} 为空，{@link HiFlowClient#instance(long)} 等返回详情时包含全部任务、轨迹与抄送。
 *
 * @param status           running | completed | canceled
 * @param result           审批结果：最后到达的、声明了 result 的结束节点的结果（如 approved、rejected），模型未声明时为空串
 * @param parentInstanceId 作为子流程发起时的父实例，否则为 null
 * @param parentTaskId     父实例中的子流程任务，否则为 null
 * @param depth            子流程嵌套层数，顶层为 0
 * @param archived         实例已归档（结束超过服务端 archive.after 后移入归档表，只读）；只在详情中有意义
 */
public record Instance(long id, long definitionId, String definitionKey, int definitionVersion, String definitionName,
                       String businessKey, String title, String starter, String status, Map<String, Object> variables,
                       OffsetDateTime startedAt, OffsetDateTime endedAt, String endReason, String result,
                       Long parentInstanceId, Long parentTaskId, int depth,
                       List<Task> tasks, List<Event> events, List<CC> cc, boolean archived) {
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String CANCELED = "canceled";
    /** 约定的审批结果：结束节点 result 为 approved / rejected 时 */
    public static final String RESULT_APPROVED = "approved";
    public static final String RESULT_REJECTED = "rejected";

    /** 实例是否仍在进行 */
    public boolean running() {
        return RUNNING.equals(status);
    }

    /** 进行中的任务 */
    public List<Task> activeTasks() {
        return tasks.stream().filter(Task::active).toList();
    }

    static Instance from(Map<String, Object> m) {
        return new Instance(Json.num(m, "id"), Json.num(m, "definition_id"), Json.str(m, "definition_key"),
                (int) Json.num(m, "definition_version"), Json.str(m, "definition_name"), Json.str(m, "business_key"),
                Json.str(m, "title"), Json.str(m, "starter"), Json.str(m, "status"), Models.vars(m, "variables"),
                Models.time(m, "started_at"), Models.time(m, "ended_at"), Json.str(m, "end_reason"),
                Json.str(m, "result"), Models.optLong(m, "parent_instance_id"), Models.optLong(m, "parent_task_id"), (int) Json.num(m, "depth"),
                Models.list(m, "tasks", Task::from), Models.list(m, "events", Event::from), Models.list(m, "cc", CC::from),
                Boolean.TRUE.equals(m.get("archived")));
    }
}
