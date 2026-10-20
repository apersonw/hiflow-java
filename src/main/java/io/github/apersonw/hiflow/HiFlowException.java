package io.github.apersonw.hiflow;

/**
 * HiFlow 返回的错误：HTTP 状态码 + 业务码（见服务端 README 的错误码表，常用的见 {@link ErrorCodes}）。
 * 网络错误以 {@link java.io.UncheckedIOException} 抛出。
 */
public class HiFlowException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final int code;
    private final String requestId;

    public HiFlowException(int statusCode, int code, String message, String requestId) {
        super(requestId == null || requestId.isEmpty()
                ? String.format("hiflow: %d %s (http %d)", code, message, statusCode)
                : String.format("hiflow: %d %s (http %d, request_id %s)", code, message, statusCode, requestId));
        this.statusCode = statusCode;
        this.code = code;
        this.requestId = requestId;
    }

    /** HTTP 状态码 */
    public int getStatusCode() {
        return statusCode;
    }

    /** 业务码，如 40401 资源不存在 */
    public int getCode() {
        return code;
    }

    public String getRequestId() {
        return requestId;
    }

    /** 资源不存在（含访问其它客户端的数据） */
    public boolean isNotFound() {
        return code == ErrorCodes.NOT_FOUND;
    }
}
