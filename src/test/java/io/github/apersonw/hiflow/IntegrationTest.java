package io.github.apersonw.hiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 端到端测试：需 HiFlow 已启动，设置 HIFLOW_ADMIN_API_KEY（HIFLOW_BASE_URL 默认 http://127.0.0.1:8081）后运行：
 * {@code mvnw test -Dtest=IntegrationTest}
 */
class IntegrationTest {
    static final String BASE = System.getenv().getOrDefault("HIFLOW_BASE_URL", "http://127.0.0.1:8081");
    static final String ADMIN_KEY = System.getenv("HIFLOW_ADMIN_API_KEY");

    static final String MODEL = """
            {"nodes": [
              {"id": "start", "type": "start"},
              {"id": "manager", "type": "user_task", "name": "经理审批", "assignee": "${manager}"},
              {"id": "gw", "type": "exclusive_gateway"},
              {"id": "director", "type": "user_task", "name": "总监审批", "candidates": ["d1", "d2"]},
              {"id": "end", "type": "end"}
            ], "edges": [
              {"id": "e1", "source": "start", "target": "manager"},
              {"id": "e2", "source": "manager", "target": "gw"},
              {"id": "e3", "source": "gw", "target": "director", "condition": "amount > 1000"},
              {"id": "e4", "source": "gw", "target": "end", "default": true},
              {"id": "e5", "source": "director", "target": "end"}
            ]}""";

    static Map<String, Object> admin(String method, String path, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(BASE + path)).header("X-Admin-Key", ADMIN_KEY)
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        var resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        var env = Json.parseObject(resp.body());
        assertEquals(0L, env.get("code"), resp.body());
        return Json.asObject(env.get("data"));
    }

    @Test
    void endToEnd() throws Exception {
        assumeTrue(ADMIN_KEY != null && !ADMIN_KEY.isEmpty(), "HIFLOW_ADMIN_API_KEY 未设置");
        var client = admin("POST", "/admin/v1/clients", "{\"client_name\":\"sdk-java-it\"}");
        var model = admin("POST", "/admin/v1/models", "{\"client_id\":" + client.get("id") + ",\"key\":\"expense\",\"name\":\"报销\",\"content\":" + MODEL + "}");
        admin("POST", "/admin/v1/models/" + model.get("id") + "/publish", "{}");

        var c = HiFlowClient.builder().baseUrl(BASE).credentials((String) client.get("client_id"), (String) client.get("client_secret")).build();
        var in = c.start(StartRequest.builder("expense").businessKey("E-1").starter("u1")
                .variable("manager", "m1").variable("amount", 5000).build());
        var task = c.todo("m1").list().get(0);
        assertEquals("E-1", task.businessKey());

        var e = assertThrows(HiFlowException.class, () -> c.complete(task.id(), "nobody", null, null));
        assertEquals(ErrorCodes.NOT_ASSIGNEE, e.getCode());

        in = c.complete(task.id(), "m1", "同意", null);
        var director = in.activeTasks().get(0);
        assertEquals("director", director.nodeId());
        assertEquals(1, c.todo("d2").total());
        in = c.complete(director.id(), "d2", null, Map.of("note", "ok"));
        assertEquals(Instance.COMPLETED, in.status());
        assertEquals("ok", c.instance(in.id()).variables().get("note"));

        // 退回：总监退回到经理，经理再次处理
        var third = c.start(StartRequest.builder("expense").starter("u1").variable("manager", "m1").variable("amount", 5000).build());
        third = c.complete(third.activeTasks().get(0).id(), "m1", null, null);
        var dirTask = third.activeTasks().get(0);
        var targets = c.returnTargets(dirTask.id());
        assertEquals("manager", targets.get(0).nodeId());
        var badReturn = assertThrows(HiFlowException.class, () -> c.returnTask(dirTask.id(), "d1", "director", null));
        assertEquals(ErrorCodes.RETURN_TARGET, badReturn.getCode());
        third = c.returnTask(dirTask.id(), "d1", "manager", "金额有误");
        assertEquals("manager", third.activeTasks().get(0).nodeId());

        // 抄送：处理时抄送给 fin，fin 查看并标记已读
        third = c.complete(third.activeTasks().get(0).id(), "m1", "已核对", null, List.of("fin"));
        assertEquals("fin", third.cc().get(0).user());
        var ccs = c.ccs("fin", true, 0, 0);
        assertEquals(1, ccs.total());
        assertEquals("m1", ccs.list().get(0).sender());
        assertTrue(c.readCC(ccs.list().get(0).id(), "fin").read());
        assertEquals(0, c.ccs("fin", true, 0, 0).total());

        // 委托代理：m1 休假，报销待办交给 agent，m1 本人仍可处理
        var delegation = c.createDelegation("m1", "agent", "expense", null, null, "休假");
        var fourth = c.start(StartRequest.builder("expense").starter("u1").variable("manager", "m1").variable("amount", 5000).build());
        var delegated = fourth.activeTasks().get(0);
        assertEquals("agent", delegated.assignee());
        assertEquals("m1", delegated.delegatedFrom());
        assertEquals(1, c.delegations("m1", null, true, 0, 0).total());
        c.deleteDelegation(delegation.id());
        var gone = assertThrows(HiFlowException.class, () -> c.deleteDelegation(delegation.id()));
        assertEquals(ErrorCodes.NOT_FOUND, gone.getCode());
        c.complete(delegated.id(), "m1", null, null);

        var second = c.start(StartRequest.builder("expense").starter("u1").variable("manager", "m1").variable("amount", 10).build());
        var recallErr = assertThrows(HiFlowException.class, () -> c.recall(second.id(), "u2"));
        assertEquals(ErrorCodes.NOT_ASSIGNEE, recallErr.getCode());
        var recalled = c.recall(second.id(), "u1");
        assertEquals(Instance.CANCELED, recalled.status());
        assertEquals("发起人撤回", recalled.endReason());
        assertEquals(4, c.instances(InstanceQuery.builder().key("expense").build()).total());
    }

    static final String FORM_MODEL = """
            {"form": {"fields": [
              {"key": "amount", "label": "金额", "type": "number", "required": true, "min": 0},
              {"key": "kind", "label": "类型", "type": "select", "options": [{"value": "travel"}, {"value": "meal"}], "default": "travel"},
              {"key": "approved", "label": "同意", "type": "boolean"}
            ]},
            "nodes": [
              {"id": "s", "type": "start", "form": {"fields": {"approved": "hidden"}}},
              {"id": "t", "type": "user_task", "assignee": "boss", "form": {"fields": {"approved": "edit"}, "required": ["approved"]}},
              {"id": "e", "type": "end"}
            ], "edges": [{"id": "1", "source": "s", "target": "t"}, {"id": "2", "source": "t", "target": "e"}]}""";

    @Test
    void form() throws Exception {
        assumeTrue(ADMIN_KEY != null && !ADMIN_KEY.isEmpty(), "HIFLOW_ADMIN_API_KEY 未设置");
        var client = admin("POST", "/admin/v1/clients", "{\"client_name\":\"sdk-java-it-form\"}");
        var model = admin("POST", "/admin/v1/models", "{\"client_id\":" + client.get("id") + ",\"key\":\"claim\",\"name\":\"报销\",\"content\":" + FORM_MODEL + "}");
        admin("POST", "/admin/v1/models/" + model.get("id") + "/publish", "{}");
        var c = HiFlowClient.builder().baseUrl(BASE).credentials((String) client.get("client_id"), (String) client.get("client_secret")).build();

        var sf = c.startForm("claim");
        assertEquals("s", sf.form().nodeId());
        assertEquals(2, sf.form().fields().size());
        assertEquals("travel", sf.form().field("kind").value());
        assertTrue(sf.form().field("amount").required());
        assertEquals(0.0, sf.form().field("amount").min());

        var bad = assertThrows(HiFlowException.class, () -> c.start(StartRequest.builder("claim").starter("u1").variable("amount", "x").build()));
        assertEquals(ErrorCodes.INVALID_FORM, bad.getCode());
        var in = c.start(StartRequest.builder("claim").starter("u1").variable("amount", 66).build());
        var task = c.task(in.activeTasks().get(0).id());
        assertEquals("t", task.form().nodeId());
        assertTrue(task.form().field("approved").editable());
        assertEquals(FormView.READ, task.form().field("amount").permission());
        assertEquals(66L, ((Number) task.form().field("amount").value()).longValue());

        var readOnly = assertThrows(HiFlowException.class, () -> c.complete(task.id(), "boss", null, Map.of("amount", 1, "approved", true)));
        assertEquals(ErrorCodes.INVALID_FORM, readOnly.getCode());
        assertEquals(Instance.COMPLETED, c.complete(task.id(), "boss", null, Map.of("approved", true)).status());
    }

    static final String APPROVAL_MODEL = """
            {"auto_pass": {"starter": true},
             "form": {"fields": [{"key": "contract", "label": "合同", "type": "file", "required": true, "accept": ".pdf"},
                                 {"key": "approved", "label": "同意", "type": "boolean"}]},
             "nodes": [{"id": "s", "type": "start", "form": {"fields": {"approved": "hidden"}}},
               {"id": "self", "type": "user_task", "assignee": "${starter}"},
               {"id": "m", "type": "user_task", "assignee": "m1", "form": {"fields": {"approved": "edit"}}},
               {"id": "d", "type": "user_task", "assignee": "d1", "form": {"fields": {"approved": "edit"}}},
               {"id": "g", "type": "exclusive_gateway"},
               {"id": "ok", "type": "end", "result": "approved"}, {"id": "no", "type": "end", "result": "rejected"}],
             "edges": [{"id": "1", "source": "s", "target": "self"}, {"id": "2", "source": "self", "target": "m"},
               {"id": "3", "source": "m", "target": "d"}, {"id": "4", "source": "d", "target": "g"},
               {"id": "5", "source": "g", "target": "no", "condition": "approved == false"}, {"id": "6", "source": "g", "target": "ok", "default": true}]}""";

    static final String TRACK_MODEL = """
            {"nodes": [{"id": "s", "type": "start"}, {"id": "pickup", "type": "receive_task", "message": "picked_up"}, {"id": "e", "type": "end"}],
             "edges": [{"id": "1", "source": "s", "target": "pickup"}, {"id": "2", "source": "pickup", "target": "e"}]}""";

    @Test
    void messages() throws Exception {
        assumeTrue(ADMIN_KEY != null && !ADMIN_KEY.isEmpty(), "HIFLOW_ADMIN_API_KEY 未设置");
        var client = admin("POST", "/admin/v1/clients", "{\"client_name\":\"sdk-java-it-messages\"}");
        var model = admin("POST", "/admin/v1/models", "{\"client_id\":" + client.get("id") + ",\"key\":\"track\",\"name\":\"快递追踪\",\"content\":" + TRACK_MODEL + "}");
        admin("POST", "/admin/v1/models/" + model.get("id") + "/publish", "{}");
        var c = HiFlowClient.builder().baseUrl(BASE).credentials((String) client.get("client_id"), (String) client.get("client_secret")).build();

        // 推送先于实例到达：按业务主键缓存，发起后由后台投递
        var bk = "SF-J-" + System.nanoTime();
        var push = MessageRequest.byBusinessKey(bk, "picked_up").key("track").messageId(bk + "-1").ttl(java.time.Duration.ofMinutes(10)).variable("site", "深圳").build();
        assertEquals(MessageResult.BUFFERED, c.sendMessage(push).status());
        var in = c.start(StartRequest.builder("track").businessKey(bk).build());
        for (int i = 0; i < 50 && !Instance.COMPLETED.equals(in.status()); i++) {
            Thread.sleep(200);
            in = c.instance(in.id());
        }
        assertEquals(Instance.COMPLETED, in.status());
        assertEquals("深圳", in.variables().get("site"));
        // 同一 message_id 重复推送
        assertEquals(MessageResult.DUPLICATE, c.sendMessage(push).status());
    }

    @Test
    void approval() throws Exception {
        assumeTrue(ADMIN_KEY != null && !ADMIN_KEY.isEmpty(), "HIFLOW_ADMIN_API_KEY 未设置");
        var client = admin("POST", "/admin/v1/clients", "{\"client_name\":\"sdk-java-it-approval\"}");
        var model = admin("POST", "/admin/v1/models", "{\"client_id\":" + client.get("id") + ",\"key\":\"approval\",\"name\":\"审批\",\"content\":" + APPROVAL_MODEL + "}");
        admin("POST", "/admin/v1/models/" + model.get("id") + "/publish", "{}");
        var c = HiFlowClient.builder().baseUrl(BASE).credentials((String) client.get("client_id"), (String) client.get("client_secret")).build();

        var sf = c.startForm("approval");
        assertEquals(".pdf", sf.form().field("contract").accept());
        var bad = assertThrows(HiFlowException.class, () -> c.start(StartRequest.builder("approval").starter("u1")
                .variable("contract", FormView.file("f-1", "a.exe", null, null)).build()));
        assertEquals(ErrorCodes.INVALID_FORM, bad.getCode());

        // 发起人是“self”的处理人：自动通过，停在 m
        var in = c.start(StartRequest.builder("approval").starter("u1").variable("contract", FormView.file("f-1", "合同.pdf", 100L, "application/pdf")).build());
        assertEquals("m", in.activeTasks().get(0).nodeId());
        assertTrue(in.events().stream().anyMatch(e -> "task_auto_passed".equals(e.type())));
        in = c.complete(in.activeTasks().get(0).id(), "m1", null, Map.of("approved", true));

        // 幂等发起、转办返回实例详情、撤回到发起人
        var idem = StartRequest.builder("approval").starter("u1").idempotencyKey("approval:J-idem-" + System.nanoTime())
                .variable("contract", FormView.file("f-2", "合同.pdf", 100L, "application/pdf")).build();
        var first = c.start(idem);
        assertEquals(first.id(), c.start(idem).id());
        var moved = c.transferInstance(first.activeTasks().get(0).id(), "m1", "m2", null);
        assertEquals(first.id(), moved.id());
        assertEquals("m2", moved.activeTasks().get(0).assignee());
        var recalled = c.recallToStarter(first.id(), "u1");
        assertEquals(Instance.RUNNING, recalled.status());
        assertEquals("u1", recalled.activeTasks().get(0).assignee());

        // 发起人催办 d；间隔内再次催办被拒绝
        var urged = c.urge(in.id(), "u1", "请尽快审批");
        assertEquals(List.of(in.activeTasks().get(0).id()), urged.urged());
        long instanceId = in.id();
        var again = assertThrows(HiFlowException.class, () -> c.urge(instanceId, "u1", null));
        assertEquals(ErrorCodes.TOO_MANY_REQUESTS, again.getCode());

        // d 退回发起人并直接回到 d
        var targets = c.returnTargets(in.activeTasks().get(0).id());
        var starter = targets.get(targets.size() - 1);
        assertTrue(starter.starter() && starter.directBack());
        in = c.returnTask(in.activeTasks().get(0).id(), "d1", starter.nodeId(), "请补充", true);
        assertEquals("d", in.activeTasks().get(0).resumeNode());
        in = c.complete(in.activeTasks().get(0).id(), "u1", null, null);
        assertEquals("d", in.activeTasks().get(0).nodeId());
        in = c.complete(in.activeTasks().get(0).id(), "d1", null, Map.of("approved", false));
        assertEquals(Instance.COMPLETED, in.status());
        assertEquals(Instance.RESULT_REJECTED, in.result());
        assertEquals(1, c.instances(InstanceQuery.builder().key("approval").result("rejected").build()).total());
    }
}
