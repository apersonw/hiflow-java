package io.github.apersonw.hiflow;

import java.util.Map;

/**
 * 服务任务调用回调（{@link WebhookEvent#SERVICE_INVOKE}）：流程推进到服务任务时，HiFlow 在后台回调客户端的回调地址，
 * 签名格式同 Webhook，密钥为客户端密钥。返回 2xx 时 {@link #response(Map)} 中的变量合并到流程变量后继续推进；
 * 非 2xx 或超时按 10s、30s、90s… 退避重试，因此同一任务可能被调用多次，请按 taskId 保证幂等。
 *
 * <pre>{@code
 * ServiceRequest req = ServiceRequest.from(WebhookVerifier.verify(clientSecret, signatureHeader, rawBody));
 * String account = accounts.open((String) req.params().get("user"), req.taskId());
 * String json = ServiceRequest.response(Map.of("account", account));
 * }</pre>
 *
 * @param attempt 第几次调用（从 1 开始）
 */
public record ServiceRequest(String service, Map<String, Object> params, String nodeId, long taskId, int attempt,
                             WebhookEvent.EventInstance instance, Map<String, Object> variables) {

    /** 解析 service.invoke 回调；其他类型抛出 IllegalArgumentException */
    public static ServiceRequest from(WebhookEvent e) {
        if (!WebhookEvent.SERVICE_INVOKE.equals(e.type())) {
            throw new IllegalArgumentException("hiflow: " + e.type() + " is not " + WebhookEvent.SERVICE_INVOKE);
        }
        var d = e.data();
        return new ServiceRequest(Json.str(d, "service"), Models.vars(d, "params"), Json.str(d, "node_id"), Json.num(d, "task_id"),
                (int) Json.num(d, "attempt"), e.instance(), Models.vars(d, "variables"));
    }

    /** 调用结果的响应体 {"variables": {...}}，variables 可为空 */
    public static String response(Map<String, ?> variables) {
        return Json.write(Map.of("variables", variables == null ? Map.of() : variables));
    }
}
