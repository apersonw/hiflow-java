package io.github.apersonw.hiflow;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * HiFlow 组织架构目录接口的服务端骨架：校验 HiFlow 的签名请求，路由到 {@link IamDirectory} 的方法，
 * 校验参数、生成能力声明（按实现了哪些可选接口），并按规范输出 JSON 与 {code, message} 错误。
 *
 * <p>与框架无关：在 Spring MVC、Servlet 等框架中把请求转为 {@link Request} 调用 {@link #handle}；
 * 也可以直接挂到 JDK 自带的 HttpServer（{@link #httpHandler}）。
 *
 * <pre>{@code
 * IamServer iam = new IamServer(new MyDirectory(), new IamServer.Options("hfdir_xxx", secret));
 * // Spring：@RequestMapping("/hiflow/v1/**")
 * IamServer.Response r = iam.handle(new IamServer.Request(request.getMethod(), pathAfter("/hiflow/v1"),
 *         IamServer.parseQuery(request.getQueryString()), request::getHeader, body));
 * return ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).body(r.body());
 * }</pre>
 *
 * Request.path 须为相对基础地址的路径（未经 URL 编码，如 /users/u1），与签名中的 Path 一致。
 */
public final class IamServer {
    public static final String SPEC_VERSION = "1.1";
    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 500;
    public static final int MAX_MANAGER_LEVELS = 10;
    private static final Duration MAX_SKEW = Duration.ofMinutes(5);
    private static final Duration NONCE_TTL = Duration.ofMinutes(10);

    /** 配置：目录 ID 与密钥为 HiFlow 管理后台新建目录连接时生成 */
    public static final class Options {
        final String directoryId;
        final String secret;
        String provider;
        int maxBatchSize = 200;
        int maxManagerLevels = 5;
        boolean recursiveMembers;
        boolean roleDepartmentScope;
        NonceStore nonces = new MemoryNonces();
        BiConsumer<Request, Exception> onError = (r, e) -> { };
        Supplier<Instant> clock = Instant::now;

        public Options(String directoryId, String secret) {
            if (directoryId == null || directoryId.isEmpty() || secret == null || secret.isEmpty()) {
                throw new IllegalArgumentException("directoryId 与 secret 必填");
            }
            this.directoryId = directoryId;
            this.secret = secret;
        }

        /** 能力声明中的实现说明（如 "acme-iam 3.2"） */
        public Options provider(String v) {
            provider = v;
            return this;
        }

        /** 批量查询一次最多的 ID 数，默认 200 */
        public Options maxBatchSize(int v) {
            maxBatchSize = v;
            return this;
        }

        /** 汇报链最多返回几级（1 ~ 10），默认 5 */
        public Options maxManagerLevels(int v) {
            maxManagerLevels = Math.max(1, Math.min(v, MAX_MANAGER_LEVELS));
            return this;
        }

        /** 部门成员支持 recursive=true */
        public Options recursiveMembers(boolean v) {
            recursiveMembers = v;
            return this;
        }

        /** 角色成员支持 department_id */
        public Options roleDepartmentScope(boolean v) {
            roleDepartmentScope = v;
            return this;
        }

        /** Nonce 记录，默认进程内存（多实例部署时建议换成 Redis 等共享存储） */
        public Options nonces(NonceStore v) {
            nonces = Objects.requireNonNull(v);
            return this;
        }

        /** 方法抛出非 IamException 的异常（响应 500）时调用，用于记录日志 */
        public Options onError(BiConsumer<Request, Exception> v) {
            onError = Objects.requireNonNull(v);
            return this;
        }

        Options clock(Supplier<Instant> v) {
            clock = v;
            return this;
        }
    }

    /** 记录 10 分钟内用过的 Nonce：首次出现返回 true */
    public interface NonceStore {
        boolean use(String nonce, Duration ttl) throws Exception;
    }

    /** 请求：path 为相对基础地址、已解码的路径；header 按名称（不区分大小写）取值 */
    public record Request(String method, String path, Map<String, List<String>> query, Function<String, String> header, byte[] body) {}

    /** 响应：JSON 文本 */
    public record Response(int status, String body) {}

    private final IamDirectory dir;
    private final Options opt;
    private final boolean search;
    private final boolean managers;
    private final boolean departments;
    private final boolean roles;
    private final boolean sync;
    private final String capabilities;

    public IamServer(IamDirectory dir, Options opt) {
        this.dir = Objects.requireNonNull(dir);
        this.opt = Objects.requireNonNull(opt);
        search = dir instanceof IamDirectory.Searcher;
        managers = dir instanceof IamDirectory.Managers;
        departments = dir instanceof IamDirectory.Departments;
        roles = dir instanceof IamDirectory.Roles;
        sync = dir instanceof IamDirectory.Syncer;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("search", search);
        f.put("managers", managers);
        f.put("departments", departments);
        f.put("recursive_members", departments && opt.recursiveMembers);
        f.put("roles", roles);
        f.put("role_department_scope", roles && opt.roleDepartmentScope);
        f.put("sync", sync);
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("spec_version", SPEC_VERSION);
        if (opt.provider != null) {
            caps.put("provider", opt.provider);
        }
        caps.put("max_batch_size", opt.maxBatchSize);
        if (managers) {
            caps.put("max_manager_levels", opt.maxManagerLevels);
        }
        caps.put("features", f);
        capabilities = Json.write(caps);
    }

    /** 处理一个请求 */
    public Response handle(Request req) {
        try {
            verify(req);
            return new Response(200, route(req));
        } catch (IamException e) {
            return error(e);
        } catch (Exception e) {
            opt.onError.accept(req, e);
            return error(new IamException(500, IamException.INTERNAL, "内部错误"));
        }
    }

    private static Response error(IamException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", e.code());
        m.put("message", e.getMessage() == null ? "" : e.getMessage());
        return new Response(e.status(), Json.write(m));
    }

    private static final IamException NOT_IMPLEMENTED = new IamException(404, IamException.NOT_IMPLEMENTED, "未实现该能力");

    private String route(Request req) throws Exception {
        String[] s = trim(req.path()).split("/", -1);
        boolean get = "GET".equalsIgnoreCase(req.method());
        boolean post = "POST".equalsIgnoreCase(req.method());
        Map<String, List<String>> q = req.query() == null ? Map.of() : req.query();
        int n = s.length;
        if (get && n == 1 && s[0].equals("capabilities")) {
            return capabilities;
        }
        if (post && n == 2 && s[0].equals("users") && s[1].equals("batch-get")) {
            return batch(req.body());
        }
        if (get && n == 1 && s[0].equals("users")) {
            require(search);
            return page(((IamDirectory.Searcher) dir).searchUsers(param(q, "keyword"), param(q, "department_id"), pageOf(q)), IamDirectory.User::toJson);
        }
        if (get && n == 2 && s[0].equals("users")) {
            IamDirectory.User u = dir.user(s[1]);
            if (u == null) {
                throw IamException.notFound("用户 " + s[1] + " 不存在");
            }
            return Json.write(u.toJson());
        }
        if (get && n == 3 && s[0].equals("users") && s[2].equals("managers")) {
            require(managers);
            int levels = 1;
            String v = param(q, "levels");
            if (!v.isEmpty()) {
                try {
                    levels = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    levels = -1;
                }
                if (levels < 1 || levels > MAX_MANAGER_LEVELS) {
                    throw IamException.badRequest("levels 需在 1 ~ " + MAX_MANAGER_LEVELS + " 之间");
                }
                levels = Math.min(levels, opt.maxManagerLevels);
            }
            List<IamDirectory.Manager> ms = ((IamDirectory.Managers) dir).managers(s[1], levels);
            List<Object> items = new ArrayList<>();
            if (ms != null) {
                ms.forEach(m -> items.add(m.toJson()));
            }
            return Json.write(Map.of("items", items));
        }
        if (get && n == 1 && s[0].equals("departments")) {
            require(departments);
            return page(((IamDirectory.Departments) dir).departments(param(q, "parent_id"), pageOf(q)), IamDirectory.Department::toJson);
        }
        if (get && n == 2 && s[0].equals("departments")) {
            require(departments);
            IamDirectory.Department d = ((IamDirectory.Departments) dir).department(s[1]);
            if (d == null) {
                throw IamException.notFound("部门 " + s[1] + " 不存在");
            }
            return Json.write(d.toJson());
        }
        if (get && n == 3 && s[0].equals("departments") && s[2].equals("members")) {
            boolean recursive = "true".equals(param(q, "recursive"));
            require(departments && (!recursive || opt.recursiveMembers));
            return page(((IamDirectory.Departments) dir).members(s[1], recursive, pageOf(q)), IamDirectory.User::toJson);
        }
        if (get && n == 1 && s[0].equals("roles")) {
            require(roles);
            return page(((IamDirectory.Roles) dir).roles(pageOf(q)), IamDirectory.Role::toJson);
        }
        if (get && n == 3 && s[0].equals("roles") && s[2].equals("members")) {
            String dept = param(q, "department_id");
            require(roles && (dept.isEmpty() || opt.roleDepartmentScope));
            return page(((IamDirectory.Roles) dir).roleMembers(s[1], dept, pageOf(q)), IamDirectory.User::toJson);
        }
        if (get && n == 2 && s[0].equals("sync")) {
            String kind = s[1];
            require(sync && (!kind.equals("departments") || departments) && (!kind.equals("roles") || roles));
            Instant since = null;
            String v = param(q, "updated_since");
            if (!v.isEmpty()) {
                try {
                    since = OffsetDateTime.parse(v).toInstant();
                } catch (DateTimeParseException e) {
                    throw IamException.badRequest("updated_since 需为 RFC 3339 时间");
                }
            }
            IamDirectory.Syncer sy = (IamDirectory.Syncer) dir;
            IamDirectory.PageRequest p = pageOf(q);
            switch (kind) {
                case "users" -> {
                    return syncPage(sy.syncUsers(since, p), IamDirectory.SyncUser::toJson);
                }
                case "departments" -> {
                    return syncPage(sy.syncDepartments(since, p), IamDirectory.SyncDepartment::toJson);
                }
                case "roles" -> {
                    return syncPage(sy.syncRoles(since, p), IamDirectory.SyncRole::toJson);
                }
                default -> {
                }
            }
        }
        throw IamException.notFound("接口不存在：" + req.method() + " " + req.path());
    }

    private static String trim(String path) {
        String p = path == null ? "" : path;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static void require(boolean on) {
        if (!on) {
            throw NOT_IMPLEMENTED;
        }
    }

    private static String param(Map<String, List<String>> q, String key) {
        List<String> v = q.get(key);
        return v == null || v.isEmpty() || v.get(0) == null ? "" : v.get(0);
    }

    private static IamDirectory.PageRequest pageOf(Map<String, List<String>> q) {
        int size = DEFAULT_PAGE_SIZE;
        String v = param(q, "page_size");
        if (!v.isEmpty()) {
            try {
                size = Integer.parseInt(v);
            } catch (NumberFormatException e) {
                size = -1;
            }
            if (size < 1 || size > MAX_PAGE_SIZE) {
                throw IamException.badRequest("page_size 需在 1 ~ " + MAX_PAGE_SIZE + " 之间");
            }
        }
        return new IamDirectory.PageRequest(size, param(q, "page_token"));
    }

    private static <T> String page(IamDirectory.Page<T> p, Function<T, Map<String, Object>> conv) {
        List<Object> items = new ArrayList<>();
        String next = "";
        if (p != null) {
            if (p.items() != null) {
                p.items().forEach(it -> items.add(conv.apply(it)));
            }
            next = p.nextPageToken() == null ? "" : p.nextPageToken();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("next_page_token", next);
        return Json.write(m);
    }

    private static <T> String syncPage(IamDirectory.SyncPage<T> p, Function<T, Map<String, Object>> conv) {
        if (p == null || p.asOf() == null) {
            throw new IamException(500, IamException.INTERNAL, "导出结果缺少 asOf");
        }
        List<Object> items = new ArrayList<>();
        if (p.items() != null) {
            p.items().forEach(it -> items.add(conv.apply(it)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("next_page_token", p.nextPageToken() == null ? "" : p.nextPageToken());
        m.put("as_of", p.asOf().toString());
        return Json.write(m);
    }

    private String batch(byte[] body) throws Exception {
        List<String> ids = new ArrayList<>();
        try {
            for (Object o : Json.list(Json.parseObject(new String(body, StandardCharsets.UTF_8)), "ids")) {
                if (o instanceof String id && !id.isEmpty()) {
                    ids.add(id);
                }
            }
        } catch (RuntimeException e) {
            throw IamException.badRequest("请求体须为 {\"ids\": [...]}");
        }
        if (ids.isEmpty()) {
            throw IamException.badRequest("ids 不能为空");
        }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        if (ids.size() > opt.maxBatchSize) {
            throw IamException.badRequest("ids 最多 " + opt.maxBatchSize + " 个");
        }
        List<IamDirectory.User> users = dir.batchUsers(unique);
        Map<String, IamDirectory.User> found = new LinkedHashMap<>();
        if (users != null) {
            for (IamDirectory.User u : users) {
                if (unique.contains(u.id())) {
                    found.putIfAbsent(u.id(), u);
                }
            }
        }
        List<Object> items = new ArrayList<>();
        found.values().forEach(u -> items.add(u.toJson()));
        List<Object> missing = new ArrayList<>();
        for (String id : unique) {
            if (!found.containsKey(id)) {
                missing.add(id);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("missing_ids", missing);
        return Json.write(m);
    }

    /** 校验签名、目录 ID、时间戳（±5 分钟）、Nonce（10 分钟内不重复）与请求体摘要 */
    private void verify(Request req) throws Exception {
        Function<String, String> h = name -> {
            String v = req.header().apply(name);
            return v == null ? "" : v;
        };
        String id = h.apply("X-Client-Id"), ts = h.apply("X-Timestamp"), nonce = h.apply("X-Nonce");
        String hash = h.apply("X-Content-SHA256"), sig = h.apply("X-Signature");
        if (id.isEmpty() || ts.isEmpty() || nonce.isEmpty() || hash.isEmpty() || sig.isEmpty()) {
            throw unauthorized("缺少签名请求头");
        }
        if (!id.equals(opt.directoryId)) {
            throw unauthorized("目录 ID 不匹配");
        }
        long sec;
        try {
            sec = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            throw unauthorized("时间戳无效");
        }
        if (Duration.between(Instant.ofEpochSecond(sec), opt.clock.get()).abs().compareTo(MAX_SKEW) > 0) {
            throw unauthorized("时间戳无效或已过期");
        }
        if (nonce.length() < 16 || nonce.length() > 64) {
            throw unauthorized("Nonce 长度需在 16 ~ 64 之间");
        }
        byte[] body = req.body() == null ? new byte[0] : req.body();
        if (!Signer.sha256Hex(body).equals(hash)) {
            throw unauthorized("请求体摘要不符");
        }
        String want = Signer.sign(opt.secret, Signer.stringToSign(req.method(), req.path(), req.query(), id, ts, nonce, hash));
        if (!MessageDigest.isEqual(want.getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8))) {
            throw unauthorized("签名无效");
        }
        boolean fresh;
        try {
            fresh = opt.nonces.use(nonce, NONCE_TTL);
        } catch (Exception e) {
            throw IamException.unavailable("Nonce 存储不可用");
        }
        if (!fresh) {
            throw unauthorized("Nonce 重复");
        }
    }

    private static IamException unauthorized(String msg) {
        return new IamException(401, IamException.UNAUTHORIZED, msg);
    }

    /** 解析查询串（application/x-www-form-urlencoded 解码） */
    public static Map<String, List<String>> parseQuery(String raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return out;
    }

    /** JDK HttpServer 的处理器：prefix 为基础地址的路径前缀（如 /hiflow/v1） */
    public HttpHandler httpHandler(String prefix) {
        String base = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return (HttpExchange ex) -> {
            try (ex; InputStream in = ex.getRequestBody()) {
                String path = ex.getRequestURI().getPath();
                Response r;
                if (!path.startsWith(base + "/")) {
                    r = error(IamException.notFound("接口不存在"));
                } else {
                    byte[] body = in.readNBytes(1 << 20);
                    r = handle(new Request(ex.getRequestMethod(), path.substring(base.length()), parseQuery(ex.getRequestURI().getRawQuery()),
                            ex.getRequestHeaders()::getFirst, body));
                }
                byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(r.status(), out.length);
                ex.getResponseBody().write(out);
            } catch (IOException e) {
                // 连接已断开
            }
        };
    }

    /** 进程内存的 Nonce 记录 */
    static final class MemoryNonces implements NonceStore {
        private final Map<String, Instant> seen = new ConcurrentHashMap<>();
        private volatile Instant lastSweep = Instant.EPOCH;

        @Override
        public boolean use(String nonce, Duration ttl) {
            Instant now = Instant.now();
            if (Duration.between(lastSweep, now).toMinutes() >= 1) {
                lastSweep = now;
                seen.values().removeIf(exp -> exp.isBefore(now));
            }
            Instant exp = now.plus(ttl);
            Instant prev = seen.putIfAbsent(nonce, exp);
            if (prev == null) {
                return true;
            }
            if (prev.isBefore(now)) {
                return seen.replace(nonce, prev, exp);
            }
            return false;
        }
    }
}
