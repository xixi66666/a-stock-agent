package com.astock.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.ai.model.chat=none")
class AStockAgentApplicationTest {

    @Test
    void contextLoadsWithoutModelCredentials() {
    }
}
