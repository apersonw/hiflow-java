package io.github.apersonw.hiflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 发起流程。
 *
 * <pre>{@code
 * StartRequest.builder("leave").businessKey("L-1001").starter("u42").variable("days", 3).variable("manager", "u7").build()
 * }</pre>
 */
public final class StartRequest {
    private final Map<String, Object> body;

    private StartRequest(Map<String, Object> body) {
        this.body = body;
    }

    /** @param key 流程标识（管理后台中模型的 key），按最新发布的版本发起 */
    public static Builder builder(String key) {
        return new Builder(key);
    }

    Map<String, Object> toBody() {
        return body;
    }

    public static final class Builder {
        private final Map<String, Object> body = new LinkedHashMap<>();
        private final Map<String, Object> variables = new LinkedHashMap<>();

        private Builder(String key) {
            body.put("key", Objects.requireNonNull(key, "key"));
        }

        /** 指定版本发起；默认最新版本 */
        public Builder version(int v) { body.put("version", v); return this; }

        /** 业务主键（如请假单号），便于反查实例 */
        public Builder businessKey(String v) { body.put("business_key", v); return this; }

        /** 实例标题，默认使用流程名称 */
        public Builder title(String v) { body.put("title", v); return this; }

        public Builder starter(String v) { body.put("starter", v); return this; }

        /** 流程变量：处理人表达式 ${var}、网关条件中使用。值为 String、Number、Boolean、Map、Collection 或 null */
        public Builder variable(String name, Object value) { variables.put(name, value); return this; }

        public Builder variables(Map<String, ?> v) { variables.putAll(v); return this; }

        public StartRequest build() {
            var b = new LinkedHashMap<>(body);
            if (!variables.isEmpty()) {
                b.put("variables", new LinkedHashMap<>(variables));
            }
            return new StartRequest(b);
        }
    }
}
