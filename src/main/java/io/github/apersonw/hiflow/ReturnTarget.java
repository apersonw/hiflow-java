package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 可退回的节点。
 *
 * @param starter    目标为开始节点：退回发起人，生成发起人的“重新提交”待办
 * @param directBack 可以选择处理后直接回到退回人（与退回人在同一分支内）
 * @param operator   该节点最近一次的处理人
 */
public record ReturnTarget(String nodeId, String name, boolean starter, boolean directBack, String operator, OffsetDateTime completedAt) {
    static ReturnTarget from(Map<String, Object> m) {
        return new ReturnTarget(Json.str(m, "node_id"), Json.str(m, "name"), Json.bool(m, "starter"), Json.bool(m, "direct_back"),
                Json.str(m, "operator"), Models.time(m, "completed_at"));
    }
}
