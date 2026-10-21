package com.dualx.core.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 口令散列装配（设计 03 §4.11）。全系统只有 {@code t_account.password_hash} 与
 * {@code t_vest.bind_secret_hash} 两处存口令，都走这一个编码器。
 *
 * <p>BCrypt 自带随机盐且不可逆，因此**不需要**再套一层"加盐"或自研散列——自研口令
 * 散列几乎必然引入可被离线破解的弱点。</p>
 *
 * <p><b>cost 用默认值 10</b>，刻意不调高：设备登录在现场是**集中发生**的（一次演习
 * 开始，几十台终端几乎同时登录），cost 每加一级就把登录端点的耗时翻倍，会把开演前的
 * 登录排队放大成可感知的延迟。而这里的威胁模型是"库被拖走后离线爆破"，
 * cost 10 已足够把单次尝试压到毫秒级以上。</p>
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
