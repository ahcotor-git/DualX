package com.dualx.core.domain;

/**
 * 账号角色（对应契约 Role）。取值即库内 t_account_role.role_code 存的英文名。
 *
 * <p>不像 Team / VestStatus 那样放在 scds-protocol：角色是业务概念，与硬件协议无关，
 * 协议层不承载它。
 */
public enum Role {
    ADMIN,
    DIRECTOR,
    OPERATOR,
    VIEWER
}
