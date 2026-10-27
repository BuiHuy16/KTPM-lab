package com.example.apigatewaylab.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder,
                        @Value("${lab.services.user-url}") String users,
                        @Value("${lab.services.order-url}") String orders,
                        @Value("${lab.internal-token}") String token) {
        if (token.length() < 32) throw new IllegalArgumentException("INTERNAL_API_TOKEN must have at least 32 characters");
        return builder.routes()
                .route("users", route -> route.path("/users/**", "/auth/**")
                        .filters(filters -> filters.removeRequestHeader("X-User-ID")
                                .removeRequestHeader("X-Internal-Token")
                                .setRequestHeader("X-Internal-Token", token))
                        .uri(users))
                .route("orders", route -> route.path("/orders", "/orders/**")
                        .filters(filters -> filters.removeRequestHeader("X-User-ID")
                                .removeRequestHeader("X-Internal-Token")
                                .setRequestHeader("X-Internal-Token", token))
                        .uri(orders))
                .build();
    }

    @Bean
    RedisRateLimiter redisRateLimiter() {
        return new RedisRateLimiter(10, 10, 1);
    }
}
