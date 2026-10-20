package com.dualx.core;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 仅测试期使用的启动类。
 *
 * <p>scds-core 是可复用库、本身没有 main，但仓储层的集成测试需要一套 Spring 上下文
 * 来装配 DataSource / JdbcClient / Flyway。放在 src/test 下，不会被打进产物。
 */
@SpringBootApplication
class TestCoreApplication {
}
