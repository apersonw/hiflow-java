package io.github.apersonw.hiflow;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 响应字段解析的公共方法 */
final class Models {
    private Models() {}

    static OffsetDateTime time(Map<String, Object> m, String key) {
        var s = Json.str(m, key);
        return s.isEmpty() ? null : OffsetDateTime.parse(s);
    }

    static List<String> strings(Map<String, Object> m, String key) {
        var out = new ArrayList<String>();
        for (var v : Json.list(m, key)) {
            out.add(String.valueOf(v));
        }
        return List.copyOf(out);
    }

    static Map<String, Object> vars(Map<String, Object> m, String key) {
        var v = Json.obj(m, key);
        return v == null ? Map.of() : v;
    }

    static <T> List<T> list(Map<String, Object> m, String key, java.util.function.Function<Map<String, Object>, T> from) {
        var out = new ArrayList<T>();
        for (var v : Json.list(m, key)) {
            out.add(from.apply(Json.asObject(v)));
        }
        return List.copyOf(out);
    }
}
