package io.github.apersonw.hiflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 实例列表查询条件 */
public final class InstanceQuery {
    private final Map<String, List<String>> query;

    private InstanceQuery(Map<String, List<String>> query) {
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

        public Builder key(String v) { return set("key", v); }

        public Builder businessKey(String v) { return set("business_key", v); }

        public Builder starter(String v) { return set("starter", v); }

        /** running | completed | canceled */
        public Builder status(String v) { return set("status", v); }

        /** 审批结果（结束节点声明的 result，如 approved、rejected） */
        public Builder result(String v) { return set("result", v); }

        public Builder keyword(String v) { return set("keyword", v); }

        /** 查询已归档的实例（服务端开启归档后，结束较久的实例只在归档中） */
        public Builder archived(boolean v) { return set("archived", v ? "true" : null); }

        public Builder page(int v) { return set("page", v > 0 ? Integer.toString(v) : null); }

        public Builder pageSize(int v) { return set("page_size", v > 0 ? Integer.toString(v) : null); }

        public InstanceQuery build() { return new InstanceQuery(new LinkedHashMap<>(q)); }
    }
}
