package com.example.apigatewaylab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

class ApigatewaylabApplicationTests {
    @Test
    void contextLoads() {
        try (var context = new SpringApplicationBuilder(ApigatewaylabApplication.class)
                .profiles("test").run("--server.port=0")) {
            org.junit.jupiter.api.Assertions.assertTrue(context.isActive());
        }
    }
}
