package com.example.apigatewaylab.config;

import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

/** A WebFilter also covers local controllers, including /dashboard. Runs after Security. */
@Component
public class RateLimitFilter implements WebFilter, Ordered {
    private final RedisRateLimiter limiter;

    public RateLimitFilter(RedisRateLimiter limiter) { this.limiter = limiter; }

    @Override
    public int getOrder() { return -50; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().equals("/actuator/health")) return chain.filter(exchange);
        String address = exchange.getRequest().getRemoteAddress() == null ? "unknown" :
                exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        // Do not trust X-Forwarded-For or a client-supplied user ID.
        return exchange.getPrincipal()
                .filter(principal -> principal instanceof Authentication auth && auth.isAuthenticated())
                .map(principal -> "user:" + principal.getName())
                .defaultIfEmpty("ip:" + address)
                .flatMap(key -> limiter.isAllowed("all-api", key))
                .flatMap(result -> {
                    result.getHeaders().forEach((name, value) -> exchange.getResponse().getHeaders().set(name, value));
                    // SCG's RedisRateLimiter fails open with remaining=-1 on a Redis error.
                    // Convert that signal into 503 so an outage cannot silently disable limits.
                    if ("-1".equals(result.getHeaders().get(limiter.getRemainingHeader()))) {
                        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                        return exchange.getResponse().setComplete();
                    }
                    if (result.isAllowed()) return chain.filter(exchange);
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders().set("Retry-After", "1");
                    return exchange.getResponse().setComplete();
                });
    }
}
