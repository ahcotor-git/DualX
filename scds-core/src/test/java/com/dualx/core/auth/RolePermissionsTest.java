package com.dualx.core.auth;

import com.dualx.core.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色 → 权限点映射（设计 03 §4.5）。纯单测，不碰数据库。
 */
class RolePermissionsTest {

    @Test
    void adminIsExpressedAsSingleWildcard_notAnEnumeratedList() {
        // 逐个列举的话，新增权限点时管理员必然漏配；一个 "*" 覆盖全部且免维护。
        assertThat(RolePermissions.of(Role.ADMIN)).containsExactly(RolePermissions.ALL);
    }

    @Test
    void directorOwnsExerciseAndCommand() {
        assertThat(RolePermissions.of(Role.DIRECTOR))
                .containsExactly("exercise:*", "command:*", "situation:read",
                        "replay:*", "map:read", "device:read");
    }

    @Test
    void operatorCanCommandAndLinkDeviceButCannotRunAnExercise() {
        // 开演 / 停演属 exercise:*，只有 DIRECTOR 及以上才有：这是本表最关键的一条边界。
        assertThat(RolePermissions.of(Role.OPERATOR))
                .containsExactly("command:*", "situation:read", "device:link:*", "map:read")
                .doesNotContain("exercise:*");
    }

    @Test
    void viewerIsReadOnly() {
        assertThat(RolePermissions.of(Role.VIEWER))
                .containsExactly("situation:read", "replay:read", "map:read", "device:read")
                .noneMatch(permission -> permission.endsWith(":*"));
    }

    @Test
    void unionOfRolesIsDeduplicatedInEnumOrder() {
        // OPERATOR 枚举序在 VIEWER 之前，故先 OPERATOR 的项，再补 VIEWER 独有的项
        // （replay:read 与 device:read；situation:read / map:read 两边都有，只留一份）。
        assertThat(RolePermissions.of(List.of(Role.VIEWER, Role.OPERATOR)))
                .containsExactly("command:*", "situation:read", "device:link:*", "map:read",
                        "replay:read", "device:read");
    }

    @Test
    void unionCollapsesToWildcardWhenAdminIsPresent() {
        // 有 ADMIN 就不该再拼上其余角色的具体项：前端少一层「通配与具体项并存怎么办」的判定。
        assertThat(RolePermissions.of(List.of(Role.ADMIN, Role.VIEWER)))
                .containsExactly(RolePermissions.ALL);
    }

    @Test
    void noRolesYieldNoPermissions() {
        assertThat(RolePermissions.of(List.of())).isEmpty();
    }
}
