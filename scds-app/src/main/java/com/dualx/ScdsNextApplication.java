package com.dualx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * scds-next 唯一可执行入口（fat jar）。
 *
 * <p>阶段 1 只装配骨架：协议层（scds-protocol）与 UWB 解算层（scds-solver）已就位，
 * 网关 / 业务 / 接口三层留空，链路与业务实现见阶段 2。</p>
 */
@SpringBootApplication
public class ScdsNextApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScdsNextApplication.class, args);
    }
}
