package io.github.apersonw.hiflow;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 分页结果 */
public record Page<T>(List<T> list, long total, int page, int pageSize) {
    static <T> Page<T> from(Map<String, Object> m, Function<Map<String, Object>, T> item) {
        return new Page<>(Models.list(m, "list", item), Json.num(m, "total"), (int) Json.num(m, "page"), (int) Json.num(m, "page_size"));
    }
}
