package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 流程定义（模型发布的不可变版本）。
 *
 * @param content 流程图（nodes + edges），业务系统可据此展示流程
 */
public record Definition(long id, String key, String name, int version, boolean suspended,
                         Map<String, Object> content, OffsetDateTime createdAt) {
    static Definition from(Map<String, Object> m) {
        return new Definition(Json.num(m, "id"), Json.str(m, "key"), Json.str(m, "name"), (int) Json.num(m, "version"),
                Json.bool(m, "suspended"), Models.vars(m, "content"), Models.time(m, "created_at"));
    }
}
