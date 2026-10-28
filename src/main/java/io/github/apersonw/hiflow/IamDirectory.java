package io.github.apersonw.hiflow;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HiFlow 组织架构目录接口（IAM Directory API v1，规范见 HiFlow 仓库 docs/iam-api.yaml）的数据源：IAM 团队实现这个接口
 * （必须的两个方法），按需再实现 {@link Managers}、{@link Departments}、{@link Roles}、{@link Searcher}，
 * 交给 {@link IamServer} 处理签名校验、路由、参数校验、能力声明与错误格式。
 *
 * <p>停用（离职、冻结）的用户仍须能按 ID 查到（status 为 disabled）；搜索、部门成员、角色成员只返回在职用户。
 */
public interface IamDirectory {
    /** 按 ID 查询用户（含停用用户）；不存在时返回 null 或抛出 {@link IamException#notFound} */
    User user(String id) throws Exception;

    /** 批量查询（ids 已去重，不超过 maxBatchSize）；不存在的 ID 不放在结果中 */
    List<User> batchUsers(List<String> ids) throws Exception;

    /** 可选：汇报链（能力 managers） */
    interface Managers {
        /** 最多 levels 级上级（已限制在 1 ~ 10），从直属上级开始 level 严格递增，空缺的级可跳过；用户不存在时抛出 notFound */
        List<Manager> managers(String userId, int levels) throws Exception;
    }

    /** 可选：部门树与成员（能力 departments；{@link IamServer.Options#recursiveMembers} 声明支持含下级部门的成员） */
    interface Departments {
        /** parentId 为空串时返回根部门；parentId 不存在时抛出 notFound */
        Page<Department> departments(String parentId, PageRequest page) throws Exception;

        /** 不存在时返回 null 或抛出 notFound */
        Department department(String id) throws Exception;

        /** 部门的在职成员；recursive 为 true 时含下级部门 */
        Page<User> members(String departmentId, boolean recursive, PageRequest page) throws Exception;
    }

    /** 可选：角色与成员（能力 roles；{@link IamServer.Options#roleDepartmentScope} 声明支持按部门限定） */
    interface Roles {
        Page<Role> roles(PageRequest page) throws Exception;

        /** 担任角色的在职用户；departmentId 非空时只返回该部门（含下级）中的 */
        Page<User> roleMembers(String code, String departmentId, PageRequest page) throws Exception;
    }

    /** 可选：按姓名或账号搜索在职用户（能力 search），departmentId 非空时限定部门（含下级） */
    interface Searcher {
        Page<User> searchUsers(String keyword, String departmentId, PageRequest page) throws Exception;
    }

    /**
     * 可选：导出接口（能力 sync，规范 1.1），HiFlow 据此维护只读的组织快照，在 IAM 长时间不可用时仍能解析处理人规则。
     * since 为 null 时全量导出（不含墓碑）；否则只返回此后（含该时刻）有变化的记录，已删除的返回墓碑。
     * 每一页的 asOf 为导出开始时的时间（翻页时保持不变，可用 {@link SyncPage#of} 编码进 token），HiFlow 下次以它为 since。
     * syncDepartments / syncRoles 只在同时实现了 {@link Departments} / {@link Roles} 时被调用。
     */
    interface Syncer {
        SyncPage<SyncUser> syncUsers(Instant since, PageRequest page) throws Exception;

        SyncPage<SyncDepartment> syncDepartments(Instant since, PageRequest page) throws Exception;

        SyncPage<SyncRole> syncRoles(Instant since, PageRequest page) throws Exception;
    }

    String ACTIVE = "active";
    String DISABLED = "disabled";

    /** 用户；title、email、mobile、primaryDepartmentId 可为 null */
    record User(String id, String name, String status, String title, String email, String mobile,
                List<String> departmentIds, String primaryDepartmentId) {
        public User(String id, String name, String status) {
            this(id, name, status, null, null, null, List.of(), null);
        }

        public boolean active() {
            return ACTIVE.equals(status);
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("status", status);
            putIf(m, "title", title);
            putIf(m, "email", email);
            putIf(m, "mobile", mobile);
            if (departmentIds != null && !departmentIds.isEmpty()) {
                m.put("department_ids", departmentIds);
            }
            putIf(m, "primary_department_id", primaryDepartmentId);
            return m;
        }
    }

    /** 部门；headUserIds 可多人、可为空；path 为从根部门到本部门的 ID（可为 null） */
    record Department(String id, String name, String parentId, List<String> headUserIds, List<String> path) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("parent_id", parentId == null ? "" : parentId);
            m.put("head_user_ids", headUserIds == null ? List.of() : headUserIds);
            if (path != null && !path.isEmpty()) {
                m.put("path", path);
            }
            return m;
        }
    }

    /** 角色（岗位 / 职能分组），流程模型按 code 引用 */
    record Role(String code, String name, String description) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", name);
            putIf(m, "description", description);
            return m;
        }
    }

    /** 汇报链中的一级上级 */
    record Manager(int level, User user) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level);
            m.put("user", user.toJson());
            return m;
        }
    }

    /** 导出的用户（含停用）与直属上级（managerId 可为 null）；deleted 为墓碑，只需 id 与 updatedAt */
    record SyncUser(User user, String managerId, Instant updatedAt, boolean deleted) {
        public static SyncUser tombstone(String id, Instant updatedAt) {
            return new SyncUser(new User(id, "", DISABLED), null, updatedAt, true);
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = deleted ? new LinkedHashMap<>(Map.of("id", user.id())) : user.toJson();
            m.put("manager_id", managerId == null ? "" : managerId);
            m.put("updated_at", updatedAt.toString());
            if (deleted) {
                m.put("deleted", true);
            }
            return m;
        }
    }

    /** 导出的部门 */
    record SyncDepartment(Department department, Instant updatedAt, boolean deleted) {
        Map<String, Object> toJson() {
            Map<String, Object> m = department.toJson();
            m.put("updated_at", updatedAt.toString());
            if (deleted) {
                m.put("deleted", true);
            }
            return m;
        }
    }

    /** 导出的角色与全部成员（含停用）；角色或成员变化都应更新 updatedAt */
    record SyncRole(Role role, List<String> memberIds, Instant updatedAt, boolean deleted) {
        Map<String, Object> toJson() {
            Map<String, Object> m = role.toJson();
            m.put("member_ids", memberIds == null ? List.of() : memberIds);
            m.put("updated_at", updatedAt.toString());
            if (deleted) {
                m.put("deleted", true);
            }
            return m;
        }
    }

    /** 导出的一页：asOf 为导出开始时的时间，翻页时每页相同 */
    record SyncPage<T>(List<T> items, String nextPageToken, Instant asOf) {
        /** 按偏移量分页一个已排序的完整列表，偏移量与 asOf 编码进 token；首页的 asOf 取 now */
        public static <T> SyncPage<T> of(List<T> all, PageRequest p, Instant now) {
            int offset = 0;
            // token 中以毫秒记录，首页也截到毫秒，保证每页的 as_of 完全相同
            Instant asOf = now.truncatedTo(ChronoUnit.MILLIS);
            if (p.token() != null && !p.token().isEmpty()) {
                try {
                    String[] parts = new String(Base64.getUrlDecoder().decode(p.token()), StandardCharsets.UTF_8).split(":");
                    if (parts.length != 3 || !parts[0].equals("s")) {
                        throw new IllegalArgumentException();
                    }
                    offset = Integer.parseInt(parts[1]);
                    asOf = Instant.ofEpochMilli(Long.parseLong(parts[2]));
                } catch (IllegalArgumentException e) {
                    throw IamException.badRequest("page_token 无效");
                }
            }
            if (offset >= all.size()) {
                return new SyncPage<>(List.of(), "", asOf);
            }
            int end = Math.min(offset + p.size(), all.size());
            String next = end < all.size()
                    ? Base64.getUrlEncoder().withoutPadding().encodeToString(("s:" + end + ":" + asOf.toEpochMilli()).getBytes(StandardCharsets.UTF_8))
                    : "";
            return new SyncPage<>(new ArrayList<>(all.subList(offset, end)), next, asOf);
        }
    }

    /** 分页参数：size 已校验（1 ~ 500，默认 100），token 为上一页的 nextPageToken（首页为空串） */
    record PageRequest(int size, String token) {}

    /** 一页结果；nextPageToken 为空串表示最后一页 */
    record Page<T>(List<T> items, String nextPageToken) {
        /** 按偏移量分页一个已排序的完整列表（数据量小、可一次读出时使用） */
        public static <T> Page<T> of(List<T> all, PageRequest p) {
            int offset = 0;
            if (p.token() != null && !p.token().isEmpty()) {
                try {
                    String raw = new String(Base64.getUrlDecoder().decode(p.token()), StandardCharsets.UTF_8);
                    if (!raw.startsWith("o:")) {
                        throw new IllegalArgumentException();
                    }
                    offset = Integer.parseInt(raw.substring(2));
                } catch (IllegalArgumentException e) {
                    throw IamException.badRequest("page_token 无效");
                }
            }
            if (offset >= all.size()) {
                return new Page<>(List.of(), "");
            }
            int end = Math.min(offset + p.size(), all.size());
            String next = end < all.size()
                    ? Base64.getUrlEncoder().withoutPadding().encodeToString(("o:" + end).getBytes(StandardCharsets.UTF_8))
                    : "";
            return new Page<>(new ArrayList<>(all.subList(offset, end)), next);
        }
    }

    private static void putIf(Map<String, Object> m, String k, String v) {
        if (v != null && !v.isEmpty()) {
            m.put(k, v);
        }
    }
}
