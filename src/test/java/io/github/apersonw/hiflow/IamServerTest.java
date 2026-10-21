package io.github.apersonw.hiflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class IamServerTest {
    static final String ID = "hfdir_demo";
    static final String SECRET = "demo-secret";
    static final String DATA = """
            {"users":[
              {"id":"ceo","name":"CEO","department_ids":["hq"]},
              {"id":"mgr","name":"经理","manager_id":"ceo","department_ids":["sales"],"status":"disabled"},
              {"id":"u1","name":"张三","manager_id":"mgr","department_ids":["east"]},
              {"id":"u2","name":"李四","manager_id":"mgr","department_ids":["east"]},
              {"id":"u3","name":"王五","manager_id":"ceo","department_ids":["sales"]}],
             "departments":[
              {"id":"hq","name":"总部","head_user_ids":["ceo"]},
              {"id":"sales","name":"销售","parent_id":"hq","head_user_ids":["mgr"]},
              {"id":"east","name":"华东","parent_id":"sales"}],
             "roles":[{"code":"fin","name":"财务","members":["u2","u3","mgr"]}]}
            """;
    static final AtomicInteger SEQ = new AtomicInteger();

    /** 对接指南中的签名示例向量 */
    @Test
    void sharedVector() {
        String sts = Signer.stringToSign("GET", "/users/u1/managers", Map.of("levels", List.of("2")), ID, "1700000000",
                "0123456789abcdef0123456789abcdef", Signer.EMPTY_SHA256);
        assertEquals("5jQothWde7V1E7buK/4g4BsnOG+suyNeoY4KXKohl0o=", Signer.sign(SECRET, sts));
    }

    /** 按 HiFlow 的方式签名的请求 */
    static IamServer.Request signed(String method, String path, Map<String, List<String>> query, String body, Map<String, String> override) {
        byte[] raw = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = String.format("nonce-%026d", SEQ.incrementAndGet());
        String hash = Signer.sha256Hex(raw);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-client-id", ID);
        h.put("x-timestamp", ts);
        h.put("x-nonce", nonce);
        h.put("x-content-sha256", hash);
        h.put("x-signature", Signer.sign(SECRET, Signer.stringToSign(method, path, query, ID, ts, nonce, hash)));
        override.forEach((k, v) -> h.put(k.toLowerCase(), v));
        return new IamServer.Request(method, path, query, name -> h.get(name.toLowerCase()), raw);
    }

    static IamServer.Response get(IamServer s, String path, Map<String, List<String>> query) {
        return s.handle(signed("GET", path, query, null, Map.of()));
    }

    @Test
    void auth() {
        IamServer s = new IamServer(new StaticIamDirectory(DATA), new IamServer.Options(ID, SECRET));
        assertEquals(200, get(s, "/capabilities", Map.of()).status());
        List<Map<String, String>> bad = List.of(
                Map.of("X-Signature", "AAAA"),
                Map.of("X-Client-Id", "other"),
                Map.of("X-Timestamp", String.valueOf(Instant.now().minusSeconds(3600).getEpochSecond())),
                Map.of("X-Content-SHA256", Signer.sha256Hex("x".getBytes(StandardCharsets.UTF_8))));
        for (Map<String, String> o : bad) {
            IamServer.Response r = s.handle(signed("GET", "/capabilities", Map.of(), null, o));
            assertEquals(401, r.status(), o.toString());
            assertTrue(r.body().contains("\"unauthorized\""));
        }
        IamServer.Request once = signed("GET", "/users/u1", Map.of(), null, Map.of());
        assertEquals(200, s.handle(once).status());
        assertEquals(401, s.handle(once).status(), "replayed nonce");
    }

    @Test
    void staticDirectory() {
        IamServer s = new IamServer(new StaticIamDirectory(DATA), new IamServer.Options(ID, SECRET).recursiveMembers(true).roleDepartmentScope(true).maxManagerLevels(3));
        Map<String, Object> caps = Json.parseObject(get(s, "/capabilities", Map.of()).body());
        Json.obj(caps, "features").values().forEach(v -> assertEquals(Boolean.TRUE, v));
        assertEquals(3L, ((Number) caps.get("max_manager_levels")).longValue());
        assertTrue(get(s, "/users/mgr", Map.of()).body().contains("\"disabled\""));
        IamServer.Response missing = get(s, "/users/nobody", Map.of());
        assertEquals(404, missing.status());
        assertTrue(missing.body().contains("\"not_found\""));
        Map<String, Object> batch = Json.parseObject(s.handle(signed("POST", "/users/batch-get", Map.of(), "{\"ids\":[\"u1\",\"u1\",\"x\"]}", Map.of())).body());
        assertEquals(1, Json.list(batch, "items").size());
        assertEquals(List.of("x"), Json.list(batch, "missing_ids"));
        // 停用的直属上级跳过但计入级数
        List<Object> ms = Json.list(Json.parseObject(get(s, "/users/u1/managers", Map.of("levels", List.of("5"))).body()), "items");
        assertEquals(1, ms.size());
        assertEquals(2L, ((Number) Json.asObject(ms.get(0)).get("level")).longValue());
        // 分页：含下级的成员逐页读取
        List<Object> ids = new ArrayList<>();
        String token = "";
        do {
            Map<String, List<String>> q = new LinkedHashMap<>(Map.of("recursive", List.of("true"), "page_size", List.of("1")));
            if (!token.isEmpty()) {
                q.put("page_token", List.of(token));
            }
            Map<String, Object> page = Json.parseObject(get(s, "/departments/sales/members", q).body());
            Json.list(page, "items").forEach(it -> ids.add(Json.asObject(it).get("id")));
            token = Json.str(page, "next_page_token");
        } while (!token.isEmpty() && ids.size() < 10);
        assertEquals(List.of("u1", "u2", "u3"), ids);
        assertEquals(1, Json.list(Json.parseObject(get(s, "/roles/fin/members", Map.of("department_id", List.of("east"))).body()), "items").size());
        assertEquals(400, get(s, "/departments", Map.of("page_size", List.of("0"))).status());
        assertEquals(400, get(s, "/departments", Map.of("page_token", List.of("bogus"))).status());
    }

    @Test
    void minimalDirectory() {
        List<Exception> logged = new ArrayList<>();
        IamDirectory minimal = new IamDirectory() {
            @Override
            public User user(String id) {
                return "u1".equals(id) ? new User("u1", "张三", ACTIVE) : null;
            }

            @Override
            public List<User> batchUsers(List<String> ids) {
                throw new IllegalStateException("db down");
            }
        };
        IamServer s = new IamServer(minimal, new IamServer.Options(ID, SECRET).recursiveMembers(true).onError((r, e) -> logged.add(e)));
        Json.obj(Json.parseObject(get(s, "/capabilities", Map.of()).body()), "features").values().forEach(v -> assertEquals(Boolean.FALSE, v));
        for (String p : List.of("/users", "/users/u1/managers", "/departments", "/departments/d/members", "/roles", "/roles/r/members")) {
            IamServer.Response r = get(s, p, Map.of());
            assertEquals(404, r.status(), p);
            assertTrue(r.body().contains("not_implemented"), p);
        }
        assertEquals(404, get(s, "/users/u2", Map.of()).status());
        IamServer.Response r = s.handle(signed("POST", "/users/batch-get", Map.of(), "{\"ids\":[\"u1\"]}", Map.of()));
        assertEquals(500, r.status());
        assertEquals(1, logged.size());
    }

    /** 契约自检：HIFLOW_IAMCHECK 为 HiFlow tools/iamcheck 的可执行文件，HIFLOW_IAM_DATA 为示例组织数据（make test-iam-skeleton） */
    @Test
    @EnabledIfEnvironmentVariable(named = "HIFLOW_IAMCHECK", matches = ".+")
    void contract() throws Exception {
        String data = Files.readString(Path.of(System.getenv("HIFLOW_IAM_DATA")));
        IamServer s = new IamServer(new StaticIamDirectory(data), new IamServer.Options(ID, SECRET).recursiveMembers(true).roleDepartmentScope(true));
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/hiflow/v1/", s.httpHandler("/hiflow/v1"));
        http.start();
        try {
            Process p = new ProcessBuilder(System.getenv("HIFLOW_IAMCHECK"), "-base", "http://127.0.0.1:" + http.getAddress().getPort() + "/hiflow/v1",
                    "-id", ID, "-secret", SECRET).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(60, TimeUnit.SECONDS));
            assertEquals(0, p.exitValue(), out);
            assertTrue(out.contains("失败 0"), out);
        } finally {
            http.stop(0);
        }
    }
}
