package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 委托代理规则：生效期内 {@code user} 的新待办（指定处理人的任务与会签处理人）改由 {@code delegate} 处理。
 *
 * @param definitionKey 只委托该流程的待办，空串表示全部流程
 * @param endsAt        结束时间（不含），null 表示长期有效
 */
public record Delegation(long id, String user, String delegate, String definitionKey, OffsetDateTime startsAt,
                         OffsetDateTime endsAt, String reason, OffsetDateTime createdAt) {
    static Delegation from(Map<String, Object> m) {
        return new Delegation(Json.num(m, "id"), Json.str(m, "user"), Json.str(m, "delegate"), Json.str(m, "definition_key"),
                Models.time(m, "starts_at"), Models.time(m, "ends_at"), Json.str(m, "reason"), Models.time(m, "created_at"));
    }
}
