package com.dualx.core;

import com.dualx.core.port.TokenSigner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import static org.mockito.Mockito.mock;

/**
 * 仅测试期使用的启动类。
 *
 * <p>scds-core 是可复用库、本身没有 main，但仓储层的集成测试需要一套 Spring 上下文
 * 来装配 DataSource / JdbcClient / Flyway。放在 src/test 下，不会被打进产物。
 */
@SpringBootApplication
class TestCoreApplication {

    /** 核心集成测试只替换签发适配器，业务服务与数据库仍使用真实实现。 */
    @Bean
    TokenSigner tokenSigner() {
        return mock(TokenSigner.class);
    }
}
