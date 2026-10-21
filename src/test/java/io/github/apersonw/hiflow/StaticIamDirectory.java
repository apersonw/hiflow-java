package io.github.apersonw.hiflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 内存目录（全部能力），数据格式与 HiFlow tools/iammock 的 JSON 文件相同：测试与契约自检用，也是各方法语义的参考 */
final class StaticIamDirectory implements IamDirectory, IamDirectory.Managers, IamDirectory.Departments, IamDirectory.Roles, IamDirectory.Searcher {
    private final Map<String, User> users = new TreeMap<>();
    private final Map<String, String> managerOf = new HashMap<>();
    private final Map<String, Department> depts = new TreeMap<>();
    private final Map<String, Role> roles = new TreeMap<>();
    private final Map<String, List<String>> roleMembers = new HashMap<>();

    StaticIamDirectory(String json) {
        Map<String, Object> data = Json.parseObject(json);
        for (Object o : Json.list(data, "users")) {
            Map<String, Object> u = Json.asObject(o);
            String status = u.get("status") == null ? ACTIVE : Json.str(u, "status");
            users.put(Json.str(u, "id"), new User(Json.str(u, "id"), Json.str(u, "name"), status, (String) u.get("title"), null, null,
                    strings(u.get("department_ids")), (String) u.get("primary_department_id")));
            managerOf.put(Json.str(u, "id"), (String) u.get("manager_id"));
        }
        for (Object o : Json.list(data, "departments")) {
            Map<String, Object> d = Json.asObject(o);
            String parent = d.get("parent_id") == null ? "" : Json.str(d, "parent_id");
            depts.put(Json.str(d, "id"), new Department(Json.str(d, "id"), Json.str(d, "name"), parent, strings(d.get("head_user_ids")), null));
        }
        for (Object o : Json.list(data, "roles")) {
            Map<String, Object> r = Json.asObject(o);
            roles.put(Json.str(r, "code"), new Role(Json.str(r, "code"), Json.str(r, "name"), (String) r.get("description")));
            roleMembers.put(Json.str(r, "code"), strings(r.get("members")));
        }
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            l.forEach(x -> out.add((String) x));
        }
        return out;
    }

    @Override
    public User user(String id) {
        return users.get(id);
    }

    @Override
    public List<User> batchUsers(List<String> ids) {
        List<User> out = new ArrayList<>();
        ids.forEach(id -> {
            if (users.containsKey(id)) {
                out.add(users.get(id));
            }
        });
        return out;
    }

    /** 停用的上级视为空缺，跳过但计入级数 */
    @Override
    public List<Manager> managers(String userId, int levels) {
        if (!users.containsKey(userId)) {
            throw IamException.notFound("用户 " + userId + " 不存在");
        }
        List<Manager> out = new ArrayList<>();
        Set<String> seen = new HashSet<>(Set.of(userId));
        String cur = managerOf.get(userId);
        for (int level = 1; cur != null && !cur.isEmpty() && level <= levels && seen.add(cur); level++) {
            User m = users.get(cur);
            if (m == null) {
                break;
            }
            if (m.active()) {
                out.add(new Manager(level, m));
            }
            cur = managerOf.get(cur);
        }
        return out;
    }

    private List<String> pathOf(String id) {
        List<String> path = new ArrayList<>();
        for (String cur = id; cur != null && !cur.isEmpty() && path.size() < 64; cur = depts.get(cur) == null ? null : depts.get(cur).parentId()) {
            path.add(0, cur);
        }
        return path;
    }

    private Department withPath(Department d) {
        return new Department(d.id(), d.name(), d.parentId(), d.headUserIds(), pathOf(d.id()));
    }

    private void requireDept(String id) {
        if (!depts.containsKey(id)) {
            throw IamException.notFound("部门 " + id + " 不存在");
        }
    }

    @Override
    public Page<Department> departments(String parentId, PageRequest page) {
        if (!parentId.isEmpty()) {
            requireDept(parentId);
        }
        List<Department> list = new ArrayList<>();
        depts.values().stream().filter(d -> d.parentId().equals(parentId)).forEach(d -> list.add(withPath(d)));
        return Page.of(list, page);
    }

    @Override
    public Department department(String id) {
        Department d = depts.get(id);
        return d == null ? null : withPath(d);
    }

    private Set<String> subtree(String root) {
        Set<String> out = new HashSet<>(Set.of(root));
        for (boolean changed = true; changed; ) {
            changed = false;
            for (Department d : depts.values()) {
                if (out.contains(d.parentId()) && out.add(d.id())) {
                    changed = true;
                }
            }
        }
        return out;
    }

    /** 在职用户：scope 非空时限定所属部门，ids 非空时限定用户（按 ID 排序） */
    private List<User> active(Set<String> scope, List<String> ids) {
        List<User> out = new ArrayList<>();
        for (User u : users.values()) {
            if (!u.active() || (ids != null && !ids.contains(u.id()))) {
                continue;
            }
            if (scope != null && u.departmentIds().stream().noneMatch(scope::contains)) {
                continue;
            }
            out.add(u);
        }
        return out;
    }

    @Override
    public Page<User> members(String departmentId, boolean recursive, PageRequest page) {
        requireDept(departmentId);
        return Page.of(active(recursive ? subtree(departmentId) : Set.of(departmentId), null), page);
    }

    @Override
    public Page<Role> roles(PageRequest page) {
        return Page.of(new ArrayList<>(roles.values()), page);
    }

    @Override
    public Page<User> roleMembers(String code, String departmentId, PageRequest page) {
        if (!roles.containsKey(code)) {
            throw IamException.notFound("角色 " + code + " 不存在");
        }
        Set<String> scope = null;
        if (!departmentId.isEmpty()) {
            requireDept(departmentId);
            scope = subtree(departmentId);
        }
        return Page.of(active(scope, roleMembers.get(code)), page);
    }

    @Override
    public Page<User> searchUsers(String keyword, String departmentId, PageRequest page) {
        Set<String> scope = null;
        if (!departmentId.isEmpty()) {
            requireDept(departmentId);
            scope = subtree(departmentId);
        }
        String k = keyword.toLowerCase();
        List<User> list = active(scope, null).stream()
                .filter(u -> k.isEmpty() || u.name().toLowerCase().contains(k) || u.id().toLowerCase().contains(k)).toList();
        return Page.of(list, page);
    }
}
