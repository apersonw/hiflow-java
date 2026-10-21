package io.github.apersonw.hiflow;

import java.util.List;
import java.util.Map;

/**
 * 处理人规则解析回调（{@link WebhookEvent#RESOLVE_USERS}）：模型中的 {@code @leader(${starter})} 推进到节点时，
 * HiFlow 回调客户端的回调地址，rule 为 "leader"，args 为替换变量后的参数。签名格式同 Webhook，密钥为客户端密钥：
 *
 * <pre>{@code
 * WebhookEvent e = WebhookVerifier.verify(clientSecret, request.getHeader(WebhookVerifier.SIGNATURE_HEADER), rawBody);
 * ResolveRequest req = ResolveRequest.from(e);
 * String json = ResolveRequest.response(List.of(leaderOf(req.args().get(0))));   // 以 200 返回，Content-Type: application/json
 * }</pre>
 *
 * 返回多人时作为候选人；返回零人或非 2xx 时本次推进失败并回滚。
 *
 * @param variables 当前流程变量
 */
public record ResolveRequest(String rule, List<String> args, String nodeId, WebhookEvent.EventInstance instance,
                             Map<String, Object> variables) {

    /** 解析 resolve.users 回调；其他类型抛出 IllegalArgumentException */
    public static ResolveRequest from(WebhookEvent e) {
        if (!WebhookEvent.RESOLVE_USERS.equals(e.type())) {
            throw new IllegalArgumentException("hiflow: " + e.type() + " is not " + WebhookEvent.RESOLVE_USERS);
        }
        var d = e.data();
        return new ResolveRequest(Json.str(d, "rule"), Models.strings(d, "args"), Json.str(d, "node_id"), e.instance(),
                Models.vars(d, "variables"));
    }

    /** 解析结果的响应体 {"users": [...]} */
    public static String response(List<String> users) {
        return Json.write(Map.of("users", List.copyOf(users)));
    }
}
