package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 可退回的节点。
 *
 * @param operator 该节点最近一次的处理人
 */
public record ReturnTarget(String nodeId, String name, String operator, OffsetDateTime completedAt) {
    static ReturnTarget from(Map<String, Object> m) {
        return new ReturnTarget(Json.str(m, "node_id"), Json.str(m, "name"), Json.str(m, "operator"), Models.time(m, "completed_at"));
    }
}
