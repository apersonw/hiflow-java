package io.github.apersonw.hiflow;

import java.util.Map;

/**
 * 发起表单：流程最新版本开始节点上的表单。
 *
 * @param form 表单视图，模型没有定义表单时为 null
 */
public record StartForm(long definitionId, String key, int version, String name, FormView form) {
    static StartForm from(Map<String, Object> m) {
        return new StartForm(Json.num(m, "definition_id"), Json.str(m, "key"), (int) Json.num(m, "version"), Json.str(m, "name"),
                FormView.from(Json.obj(m, "form")));
    }
}
