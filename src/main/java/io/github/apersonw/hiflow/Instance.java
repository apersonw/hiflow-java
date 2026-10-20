package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 流程实例。列表查询时 {@code tasks}、{@code events} 为空，{@link HiFlowClient#instance(long)} 等返回详情时包含全部任务与轨迹。
 *
 * @param status running | completed | canceled
 */
public record Instance(long id, long definitionId, String definitionKey, int definitionVersion, String definitionName,
                       String businessKey, String title, String starter, String status, Map<String, Object> variables,
                       OffsetDateTime startedAt, OffsetDateTime endedAt, String endReason,
                       List<Task> tasks, List<Event> events) {
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String CANCELED = "canceled";

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
                Models.list(m, "tasks", Task::from), Models.list(m, "events", Event::from));
    }
}
