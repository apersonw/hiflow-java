package io.github.apersonw.hiflow;

/** 目录接口返回给 HiFlow 的错误：HTTP 状态码 + {code, message}。方法抛出的其它异常记为 500 internal */
public class IamException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public static final String BAD_REQUEST = "bad_request";
    public static final String UNAUTHORIZED = "unauthorized";
    public static final String NOT_FOUND = "not_found";
    public static final String NOT_IMPLEMENTED = "not_implemented";
    public static final String INTERNAL = "internal";
    public static final String UNAVAILABLE = "unavailable";

    private final int status;
    private final String code;

    public IamException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 用户、部门、角色不存在 */
    public static IamException notFound(String message) {
        return new IamException(404, NOT_FOUND, message);
    }

    public static IamException badRequest(String message) {
        return new IamException(400, BAD_REQUEST, message);
    }

    /** IAM 暂时不可用（HiFlow 按目录配置使用过期缓存） */
    public static IamException unavailable(String message) {
        return new IamException(503, UNAVAILABLE, message);
    }
}
