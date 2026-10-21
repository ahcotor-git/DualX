package com.dualx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * scds-next 唯一可执行入口（fat jar）。
 *
 * <p>模块装配：协议层（{@code scds-protocol}）与 UWB 解算层（{@code scds-solver}）已就位；
 * 领域层（{@code scds-domain}）承载领域对象与硬件端口；业务（{@code scds-core}）、
 * 硬件网关（{@code scds-gateway}）与接口（{@code scds-api}）由本模块组装。</p>
 *
 * <p>{@code @EnableScheduling} 刻意只加在唯一入口这一处：演习心跳（设计 01）、
 * WS 空闲连接扫描（设计 02）等定时任务都需要它。若各模块自行开启，会出现
 * 多个 {@code ScheduledAnnotationBeanPostProcessor} 重复装配，任务被注册多次。</p>
 */
@SpringBootApplication
@EnableScheduling
public class ScdsNextApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScdsNextApplication.class, args);
    }
}
