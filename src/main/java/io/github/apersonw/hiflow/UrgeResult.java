package io.github.apersonw.hiflow;

import java.util.List;
import java.util.Map;

/**
 * 催办结果。
 *
 * @param urged   本次催办的待办 ID
 * @param skipped 距上次催办不足最小间隔而跳过的待办 ID
 */
public record UrgeResult(long instanceId, List<Long> urged, List<Long> skipped) {
    static UrgeResult from(Map<String, Object> m) {
        return new UrgeResult(Json.num(m, "instance_id"), ids(m.get("urged")), ids(m.get("skipped")));
    }

    private static List<Long> ids(Object v) {
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(x -> ((Number) x).longValue()).toList();
    }
}
