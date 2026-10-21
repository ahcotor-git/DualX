package com.dualx.core.auth;

import com.dualx.core.domain.Role;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色 → 权限点映射（设计 03 §4.5）。静态常量表，不进数据库。
 *
 * <p>不进库的理由是「权限点漂移」：库里多出一条代码不认识的权限点、或代码新增了权限点
 * 而库里没人配，两者都只在运行时才炸。角色只有四个、权限点集合随代码演进，放在代码里
 * 天然与版本同源。</p>
 *
 * <p>取值刻意保留通配形态（如 {@code exercise:*}）而不展开成枚举清单：完整权限点清单是
 * 设计 03 Q6 的待裁定项，在这里展开等于替它拍板。前端按前缀匹配即可；将来 Q6 若改为
 * 枚举清单，只改本文件的字面量，接口形状不变。</p>
 */
public final class RolePermissions {

    /** 全量通配，仅 {@link Role#ADMIN} 使用。 */
    public static final String ALL = "*";

    private static final Map<Role, List<String>> BY_ROLE = Map.of(
            Role.ADMIN, List.of(ALL),
            Role.DIRECTOR, List.of("exercise:*", "command:*", "situation:read",
                    "replay:*", "map:read", "device:read"),
            Role.OPERATOR, List.of("command:*", "situation:read", "device:link:*", "map:read"),
            Role.VIEWER, List.of("situation:read", "replay:read", "map:read", "device:read"));

    private RolePermissions() {
    }

    /** 单个角色拥有的权限点。 */
    public static List<String> of(Role role) {
        return BY_ROLE.getOrDefault(role, List.of());
    }

    /**
     * 多角色的权限点并集，按角色枚举序稳定去重。
     *
     * <p>命中 {@link Role#ADMIN} 时直接返回单个 {@link #ALL}，不再拼上其余角色的具体项：
     * 管理员是「全部」而且必须自动涵盖新增权限点，逐个列举一定会漏。顺带也让前端少一层
     * 「同时有通配与具体项时怎么办」的判定。</p>
     *
     * <p>顺序稳定是为了让 {@code /auth/me} 的响应可预期——前端可能直接照它渲染，
     * 顺序抖动会让每次刷新后的排列都不一样。</p>
     */
    public static List<String> of(Collection<Role> roles) {
        if (roles.contains(Role.ADMIN)) {
            return List.of(ALL);
        }
        Set<String> merged = new LinkedHashSet<>();
        for (Role role : Role.values()) {
            if (roles.contains(role)) {
                merged.addAll(of(role));
            }
        }
        return List.copyOf(merged);
    }
}
