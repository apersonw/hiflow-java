package io.github.apersonw.hiflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务列表查询条件 */
public final class TaskQuery {
    private final Map<String, List<String>> query;

    private TaskQuery(Map<String, List<String>> query) {
        this.query = query;
    }

    public static Builder builder() {
        return new Builder();
    }

    Map<String, List<String>> toQuery() {
        return query;
    }

    public static final class Builder {
        private final Map<String, List<String>> q = new LinkedHashMap<>();

        private Builder set(String k, String v) {
            if (v != null && !v.isEmpty()) {
                q.put(k, List.of(v));
            } else {
                q.remove(k);
            }
            return this;
        }

        public Builder instanceId(long v) { return set("instance_id", v > 0 ? Long.toString(v) : null); }

        /** active | completed | canceled */
        public Builder status(String v) { return set("status", v); }

        /** 该用户的待办（处理人是他，或未指定处理人且他在候选人中） */
        public Builder user(String v) { return set("user", v); }

        public Builder assignee(String v) { return set("assignee", v); }

        public Builder keyword(String v) { return set("keyword", v); }

        public Builder page(int v) { return set("page", v > 0 ? Integer.toString(v) : null); }

        public Builder pageSize(int v) { return set("page_size", v > 0 ? Integer.toString(v) : null); }

        public TaskQuery build() { return new TaskQuery(new LinkedHashMap<>(q)); }
    }
}
