package io.github.apersonw.hiflow;

/** 常用业务码（与服务端 pkg/errs 一致） */
public final class ErrorCodes {
    private ErrorCodes() {}

    public static final int BAD_REQUEST = 40001;
    /** 不能退回到该节点 */
    public static final int RETURN_TARGET = 40003;
    /** 转办、加签、委托的目标用户在组织架构中不存在或已停用（目录校验为 strict） */
    public static final int INVALID_USER = 40004;
    /** 提交的表单字段类型不对、必填未填或在本节点不可修改 */
    public static final int INVALID_FORM = 40005;
    public static final int MISSING_AUTH = 40101;
    public static final int INVALID_CLIENT = 40102;
    public static final int TIMESTAMP_EXPIRED = 40103;
    public static final int NONCE_REPLAY = 40104;
    public static final int SIGNATURE = 40105;
    public static final int IP_FORBIDDEN = 40301;
    /** 当前用户不是任务的处理人 / 不在候选人中 */
    public static final int NOT_ASSIGNEE = 40302;
    public static final int NOT_FOUND = 40401;
    public static final int CONFLICT = 40901;
    /** 流程实例已结束（完成或取消） */
    public static final int INSTANCE_ENDED = 40902;
    /** 任务已处理或已取消（如被其他候选人抢先处理） */
    public static final int TASK_NOT_ACTIVE = 40903;
    /** 流程定义已挂起，不能发起新实例 */
    public static final int DEFINITION_SUSPENDED = 40904;
    /** 已有任务被处理，不能撤回 */
    public static final int RECALL_NOT_ALLOWED = 40905;
    /** 实例中没有进行中的人工待办可催办 */
    public static final int NOTHING_TO_URGE = 40906;
    /** 流程设置了“同一业务主键只允许一个进行中的实例”，该业务主键已有进行中的实例（消息中带实例 ID） */
    public static final int BUSINESS_KEY_RUNNING = 40907;
    /** 实例已挂起，恢复后才能处理任务、撤回、催办 */
    public static final int INSTANCE_SUSPENDED = 40908;
    public static final int PAYLOAD_TOO_LARGE = 41301;
    /** 流程推进失败（排他网关没有满足条件的出口等），通常是流程变量缺失 */
    public static final int EVALUATE = 42201;
    public static final int TOO_MANY_REQUESTS = 42901;
    public static final int INTERNAL = 50000;
    public static final int DEPENDENCY = 50003;
    /** HiFlow 回调业务系统失败（如处理人规则解析） */
    public static final int CALLBACK = 50201;
}
