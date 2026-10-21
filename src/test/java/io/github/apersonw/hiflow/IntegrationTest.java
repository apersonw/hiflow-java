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
}
