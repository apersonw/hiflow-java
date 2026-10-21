# HiFlow Java SDK

HiFlow 流程引擎 Open API 的 Java 客户端：请求签名、发起流程、待办查询与处理。Java 17+，零运行时依赖。

```xml
<dependency>
  <groupId>io.github.apersonw</groupId>
  <artifactId>hiflow-sdk</artifactId>
  <version>0.1.2</version>
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
| `complete(taskId, operator, comment, variables[, cc])` | 完成任务，流程继续推进；可同时抄送 |
| `addSigners(taskId, operator, users, comment)` / `removeSigners(...)` | 会签加签 / 减签 |
| `createDelegation(user, delegate, definitionKey, startsAt, endsAt, reason)` / `delegations(...)` / `deleteDelegation(id)` | 委托代理：生效期内委托人的新待办交给代理人（`Task.delegatedFrom()` 记录委托人） |
| `ccs(user, unreadOnly, page, pageSize)` / `readCC(id, user)` | 抄送记录（“抄送我的”）/ 标记已读 |
| `claim(taskId, user)` | 认领候选任务 |
| `returnTargets(taskId)` / `returnTask(taskId, operator, targetNode, comment)` | 可退回的节点 / 退回到处理过的节点 |
| `signal(instanceId, message, variables, operator)` | 发送消息，推进等待该消息的接收任务 |
| `recall(id, operator)` | 发起人撤回（还没有任务被处理时） |
| `transfer(taskId, operator, to, comment)` | 转办 |

## 待办嵌入令牌

为当前登录用户签发嵌入令牌（本地计算，不请求 HiFlow），交给业务前端的[浏览器 SDK / 嵌入组件](../browser/README.md)：

```java
EmbedToken tok = client.createEmbedToken(new EmbedToken.Options(userId).expiresIn(Duration.ofHours(2)));
```

## 处理人规则回调

模型中的处理人可以写 `@leader(${starter})` 这类规则，HiFlow 推进到该节点时回调客户端的回调地址（管理后台配置）解析成用户。签名格式同 Webhook，密钥为客户端密钥：

```java
WebhookEvent e = WebhookVerifier.verify(clientSecret, request.getHeader(WebhookVerifier.SIGNATURE_HEADER), rawBody);
ResolveRequest req = ResolveRequest.from(e);          // req.rule() == "leader"，req.args() == ["u1"]
String body = ResolveRequest.response(List.of(directory.leaderOf(req.args().get(0))));
// 以 200、Content-Type: application/json 返回 body
```

返回多人时作为候选人；返回零人、非 2xx 或超时时本次推进失败（`ErrorCodes.CALLBACK` / 42201）并整体回滚。

服务任务（`service_task`）用同一个回调地址，`event.type()` 为 `WebhookEvent.SERVICE_INVOKE`，用 `ServiceRequest.from(event)` 解析、
`ServiceRequest.response(variables)` 生成响应；非 2xx 时 HiFlow 退避重试，请按 `taskId()` 保证幂等。

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

## 组织架构目录接口（IAM 侧服务端骨架）

IAM 团队实现 HiFlow 目录接口（规范 docs/iam-api.yaml）时，实现 `IamDirectory`（必须的 `user`、`batchUsers`，按需再实现
`IamDirectory.Managers`、`Departments`、`Roles`、`Searcher`），交给 `IamServer` 处理签名校验、路由、参数校验、能力声明与错误格式：

```java
IamServer iam = new IamServer(new MyDirectory(), new IamServer.Options("hfdir_xxx", secret).recursiveMembers(true));

// Spring MVC：path 为相对基础地址 /hiflow/v1 的路径（与签名一致），body 为原始字节
@RequestMapping("/hiflow/v1/**")
ResponseEntity<String> iam(HttpServletRequest req, @RequestBody(required = false) byte[] body) {
    String path = req.getRequestURI().substring((req.getContextPath() + "/hiflow/v1").length());
    IamServer.Response r = iam.handle(new IamServer.Request(req.getMethod(), URLDecoder.decode(path, UTF_8),
            IamServer.parseQuery(req.getQueryString()), req::getHeader, body == null ? new byte[0] : body));
    return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.body());
}
```

不用框架时可直接挂到 JDK HttpServer：`server.createContext("/hiflow/v1/", iam.httpHandler("/hiflow/v1"))`。
用户、部门不存在时返回 null 或抛出 `IamException.notFound(...)`；其它异常记为 500（`Options.onError` 记录日志）。
多实例部署时把 `Options.nonces` 换成共享存储。对接完成后用 HiFlow 的 `tools/iamcheck` 或管理后台“测试连接”做契约自检。

## 测试

```bash
./mvnw test                                                       # 单元测试
HIFLOW_ADMIN_API_KEY=xxx ./mvnw test -Dtest=IntegrationTest       # 端到端（需服务已启动）
```
