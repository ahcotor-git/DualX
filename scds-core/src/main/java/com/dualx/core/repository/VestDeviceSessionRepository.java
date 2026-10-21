package com.dualx.core.repository;

import com.dualx.core.support.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 马甲设备会话（{@code t_vest} 的 {@code device_session_sid} / {@code device_login_at}
 * 两列，设计 03 §4.9）。
 *
 * <p>刻意不建会话表：会话状态与台账同处一行，「重复登录顶掉旧令牌」就只是写入新 sid 的
 * 天然结果，不存在会话表与台账不一致的中间态（旧系统的 {@code t_com} 正是把运行态与
 * 配置塞进同一行，进程重启后库里留着假象）。</p>
 *
 * <p>{@code device_session_sid} 置 NULL 即表示该设备当前无有效会话（登出 / 换绑 / 退役）。
 * 定位恒以主键 {@code vest_num}，故不额外建索引。</p>
 */
@Repository
public class VestDeviceSessionRepository {

    private final JdbcClient jdbc;

    public VestDeviceSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 该设备当前有效的会话 ID。
     *
     * <p>NULL 在 SQL 里就滤掉，于是「马甲不存在」与「该马甲无有效会话」都归为 empty——
     * 校验设备令牌时这两种情况都是拒绝，调用方不需要区分，也就不会有人误写成
     * 「empty 代表马甲不存在」。</p>
     */
    public Optional<String> findSessionSid(int vestNum) {
        return jdbc.sql("""
                        SELECT device_session_sid
                          FROM t_vest
                         WHERE vest_num = :vestNum
                           AND device_session_sid IS NOT NULL
                        """)
                .param("vestNum", vestNum)
                .query(String.class)
                .optional();
    }

    /**
     * 设备登录：写入新会话 ID，并把 {@code bound} 置 1。旧令牌的 sid 与库中不再相等，即天然失效。
     *
     * <p><b>为什么 {@code bound} 也在这条 UPDATE 里</b>：绑定密钥校验通过就是「已被某个单兵
     * 终端认领」的充要条件（决策 04 D03-3）。分两条 UPDATE 写会出现「sid 已换新但 bound 还是
     * 0」的中间态，且多一次往返；合在一条语句里则整个登录写入天然原子。</p>
     *
     * @return 影响行数；0 表示该马甲不在台账中（或编号超出 CHECK 约束），调用方应拒登
     */
    public int openSession(int vestNum, String sid, Instant loginAt) {
        return jdbc.sql("""
                        UPDATE t_vest
                           SET device_session_sid = :sid,
                               device_login_at = :loginAt,
                               bound = 1
                         WHERE vest_num = :vestNum
                        """)
                .param("vestNum", vestNum)
                .param("sid", sid)
                .param("loginAt", SqlTime.toDb(loginAt))
                .update();
    }

    /**
     * 登出 / 换绑：置 NULL。
     *
     * <p>{@code device_login_at} 不随登出清空：它是「最后登录时刻」，清掉就丢了排障依据。</p>
     */
    public int clearSession(int vestNum) {
        return jdbc.sql("UPDATE t_vest SET device_session_sid = NULL WHERE vest_num = :vestNum")
                .param("vestNum", vestNum)
                .update();
    }
}
