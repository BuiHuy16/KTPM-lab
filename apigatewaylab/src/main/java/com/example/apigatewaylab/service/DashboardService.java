package com.example.apigatewaylab.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class DashboardService {
    private final WebClient users;
    private final WebClient orders;

    public DashboardService(WebClient.Builder builder,
                            @Value("${lab.services.user-url}") String userUrl,
                            @Value("${lab.services.order-url}") String orderUrl,
                            @Value("${lab.internal-token}") String internalToken) {
        users = builder.clone().baseUrl(userUrl).defaultHeader("X-Internal-Token", internalToken).build();
        orders = builder.clone().baseUrl(orderUrl).defaultHeader("X-Internal-Token", internalToken).build();
    }

    public Mono<Dashboard> load(Jwt jwt) {
        // Each service derives the same user ID from this verified JWT, never from query parameters.
        var user = users.get().uri("/users/me").headers(h -> h.setBearerAuth(jwt.getTokenValue()))
                .retrieve().bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
        var orderList = orders.get().uri("/orders/me").headers(h -> h.setBearerAuth(jwt.getTokenValue()))
                .retrieve().bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        return Mono.zip(user, orderList, Dashboard::new).timeout(Duration.ofSeconds(5))
                .onErrorMap(error -> new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Dashboard dependency unavailable", error));
    }

    public record Dashboard(Map<String, Object> user, List<Map<String, Object>> orders) {}
}
