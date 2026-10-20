# HiFlow Java SDK

HiFlow 流程引擎 Open API 的 Java 客户端：请求签名、发起流程、待办查询与处理。Java 17+，零运行时依赖。

```xml
<dependency>
  <groupId>io.github.apersonw</groupId>
  <artifactId>hiflow-sdk</artifactId>
  <version>0.1.1</version>
</dependency>
```

```java
var client = HiFlowClient.builder()
        .baseUrl("https://flow.example.com/api")
        .credentials(System.getenv("HIFLOW_CLIENT_ID"), System.getenv("HIFLOW_CLIENT_SECRET"))
        .build();

// 发起：key 为管理后台中流程模型的标识，按最新发布的版本执行
var instance = client.start(StartRequest.builder("leave")
        .businessKey("L-1001").starter("u42")
        .variable("days", 3).variable("manager", "u7")
        .build());

// 待办：处理人是 u7，或未指定处理人且 u7 在候选人中
var todo = client.todo("u7");

// 处理：审批结果写入流程变量，由排他网关的条件决定走向
client.complete(todo.list().get(0).id(), "u7", "同意", Map.of("approved", true));
```

| 方法 | 说明 |
|---|---|
| `definition(key)` | 流程定义的最新版本（含流程图） |
| `start(request)` | 发起流程，返回实例详情（含生成的待办） |
| `instance(id)` / `instances(query)` | 实例详情（含任务与轨迹）/ 分页查询 |
| `cancel(id, operator, reason)` | 撤回 / 终止实例 |
| `tasks(query)` / `todo(user)` / `task(id)` | 任务查询 / 用户待办 / 任务详情 |
| `complete(taskId, operator, comment, variables)` | 完成任务，流程继续推进 |
| `claim(taskId, user)` | 认领候选任务 |
| `returnTargets(taskId)` / `returnTask(taskId, operator, targetNode, comment)` | 可退回的节点 / 退回到处理过的节点 |
| `recall(id, operator)` | 发起人撤回（还没有任务被处理时） |
| `transfer(taskId, operator, to, comment)` | 转办 |

## 接收事件（Webhook）

```java
// rawBody 为原始请求体字节；签名无效抛出 WebhookSignatureException，应返回 401
WebhookEvent event = WebhookVerifier.verify(secret, request.getHeader(WebhookVerifier.SIGNATURE_HEADER), rawBody);
switch (event.type()) {
    case WebhookEvent.TASK_CREATED -> notify(event.task().assignee(), event.instance().title());
    case WebhookEvent.INSTANCE_COMPLETED -> markApproved(event.instance().businessKey());
    default -> { }
}
// 返回 2xx 表示已处理；非 2xx 时 HiFlow 按退避重试，请按 event.id() 去重
```

服务端错误抛出 `HiFlowException`（`getCode()` 为业务码，常量见 `ErrorCodes`），网络错误抛出 `UncheckedIOException`。
查询请求自动重试（`retries`），写操作不重试；时钟偏差超窗时自动校准。`HiFlowClient` 线程安全，建议全局复用。

## 测试

```bash
./mvnw test                                                       # 单元测试
HIFLOW_ADMIN_API_KEY=xxx ./mvnw test -Dtest=IntegrationTest       # 端到端（需服务已启动）
```
