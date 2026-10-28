package io.github.apersonw.hiflow;

import java.util.List;
import java.util.Map;

/**
 * 某个节点上的表单：字段、本节点的权限与当前值（隐藏的字段不返回）。
 * 提交时只传可编辑的字段；类型、必填或权限不符时抛出 {@link ErrorCodes#INVALID_FORM}。
 *
 * @param nodeId 节点 ID
 * @param fields 字段（按模型中的顺序）
 */
public record FormView(String nodeId, List<Field> fields) {
    public static final String EDIT = "edit";
    public static final String READ = "read";

    /**
     * 附件字段的值：文件由业务系统自己存储（HiFlow 不接入任何文件服务），{@code id} 为业务系统的文件标识；size、type 为 null 时不参与校验。
     * 作为 file 字段的变量值提交，files 字段提交其列表。
     */
    public static Map<String, Object> file(String id, String name, Long size, String type) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", java.util.Objects.requireNonNull(id, "id"));
        m.put("name", java.util.Objects.requireNonNull(name, "name"));
        if (size != null) {
            m.put("size", size);
        }
        if (type != null) {
            m.put("type", type);
        }
        return m;
    }

    /**
     * 表单字段（key 即流程变量名）。
     *
     * @param type       text | textarea | number | boolean | date（2006-01-02）| datetime（RFC 3339）| select | multiselect | user | users
     *                   | file | files（附件，值为 {@code {id, name, size?, type?, url?}} 对象 / 数组，见 {@link FormView#file}）
     * @param required   本节点必须有值
     * @param defaultValue 发起时未提交则使用的默认值，没有为 null
     * @param min        number 为最小值，text / textarea 为最少字数，multiselect / users 为最少项数；没有为 null
     * @param accept     附件允许的文件类型（扩展名或 MIME 类型，逗号分隔），没有为空串
     * @param maxSize    附件单个文件大小上限（字节），没有为 0
     * @param permission 本节点的权限：{@link #EDIT} 或 {@link #READ}
     * @param value      当前值（发起表单为默认值），没有为 null
     */
    public record Field(String key, String label, String type, boolean required, List<Option> options, Object defaultValue,
                        String placeholder, String help, Double min, Double max, String pattern, String accept, long maxSize,
                        String permission, Object value) {
        public boolean editable() {
            return EDIT.equals(permission);
        }

        static Field from(Map<String, Object> m) {
            return new Field(Json.str(m, "key"), Json.str(m, "label"), Json.str(m, "type"), Json.bool(m, "required"),
                    Models.list(m, "options", Option::from), m.get("default"), Json.str(m, "placeholder"), Json.str(m, "help"),
                    m.get("min") instanceof Number n ? n.doubleValue() : null, m.get("max") instanceof Number n ? n.doubleValue() : null,
                    Json.str(m, "pattern"), Json.str(m, "accept"), Json.num(m, "max_size"), Json.str(m, "permission"), m.get("value"));
        }
    }

    /** 单选 / 多选的选项 */
    public record Option(String value, String label) {
        static Option from(Map<String, Object> m) {
            return new Option(Json.str(m, "value"), Json.str(m, "label"));
        }
    }

    /** 按 key 查找字段，没有时为 null */
    public Field field(String key) {
        return fields.stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
    }

    static FormView from(Map<String, Object> m) {
        return m == null ? null : new FormView(Json.str(m, "node_id"), Models.list(m, "fields", Field::from));
    }
}
