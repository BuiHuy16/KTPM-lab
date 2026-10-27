package com.example.apigatewaylab;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.*;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import org.springframework.cloud.gateway.filter.ratelimit.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;


import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GatewayHttpTests {
    private static final String SECRET = "test-only-jwt-signing-secret-at-least-32-bytes";
    private static final String INTERNAL = "test-only-internal-secret-at-least-32-chars";
    private static final AtomicReference<String> USER_TOKEN = new AtomicReference<>();
    private static final AtomicReference<String> ORDER_TOKEN = new AtomicReference<>();
    private static final AtomicReference<String> SPOOFED_USER = new AtomicReference<>();
    private static final AtomicBoolean FAIL_ORDERS = new AtomicBoolean();
    private static final HttpServer USERS = server(true);
    private static final HttpServer ORDERS = server(false);

    static ConfigurableApplicationContext context;
    static RedisRateLimiter limiter;
    static int port;
    WebTestClient client;

    @TestConfiguration(proxyBeanMethods = false)
    static class LimiterTestConfig {
        @Bean @Primary
        RedisRateLimiter testLimiter() { return mock(RedisRateLimiter.class); }
    }

    @BeforeAll
    static void startApplication() {
        context = new SpringApplicationBuilder(ApigatewaylabApplication.class, LimiterTestConfig.class)
                .profiles("test").run("--server.port=0",
                        "--lab.services.user-url=http://localhost:" + USERS.getAddress().getPort(),
                        "--lab.services.order-url=http://localhost:" + ORDERS.getAddress().getPort());
        limiter = context.getBean("testLimiter", RedisRateLimiter.class);
        port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    @BeforeEach
    void setup() {
        reset(limiter);
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        FAIL_ORDERS.set(false);
        when(limiter.getRemainingHeader()).thenReturn("X-RateLimit-Remaining");
        when(limiter.isAllowed(anyString(), anyString())).thenReturn(Mono.just(new RateLimiter.Response(true, Map.of())));
    }

    @AfterAll
    static void closeServers() { if (context != null) context.close(); USERS.stop(0); ORDERS.stop(0); }

    @Test
    void missingInvalidExpiredWrongIssuerOrSubjectJwtReturns401() {
        client.get().uri("/users/me").exchange().expectStatus().isUnauthorized();
        for (String token : new String[]{"not-a-jwt", token("1", "apigatewaylab", -600),
                token("1", "other-issuer", 3600), token("attacker", "apigatewaylab", 3600)}) {
            client.get().uri("/users/me").headers(h -> h.setBearerAuth(token))
                    .exchange().expectStatus().isUnauthorized();
        }
    }

    @Test
    void routesUsersAndOrdersAndReplacesSpoofedInternalHeaders() {
        client.get().uri("/users/me").headers(h -> h.setBearerAuth(token("1", "apigatewaylab", 3600)))
                .header("X-Internal-Token", "attacker").header("X-User-ID", "99")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.username").isEqualTo("huy");
        Assertions.assertEquals(INTERNAL, USER_TOKEN.get());
        Assertions.assertNull(SPOOFED_USER.get());
        client.get().uri("/orders/me").headers(h -> h.setBearerAuth(token("1", "apigatewaylab", 3600)))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].productName").isEqualTo("Keyboard");
        Assertions.assertEquals(INTERNAL, ORDER_TOKEN.get());
    }

    @Test
    void anonymousAuthRouteWorksAndIsLimitedBySocketAddress() {
        client.post().uri("/auth/login").bodyValue(Map.of("username", "huy", "password", "password123"))
                .exchange().expectStatus().isOk();
        verify(limiter).isAllowed(eq("all-api"), startsWith("ip:"));
    }

    @Test
    void dashboardAggregatesAndForwardsJwtToBothServices() {
        client.get().uri("/dashboard?userId=99").headers(h -> h.setBearerAuth(token("1", "apigatewaylab", 3600)))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.user.id").isEqualTo(1).jsonPath("$.orders[0].productName").isEqualTo("Keyboard");
        Assertions.assertEquals(INTERNAL, USER_TOKEN.get());
        Assertions.assertEquals(INTERNAL, ORDER_TOKEN.get());
        verify(limiter).isAllowed("all-api", "user:1");
    }

    @Test
    void dashboardDependencyFailureReturns502() {
        FAIL_ORDERS.set(true);
        client.get().uri("/dashboard").headers(h -> h.setBearerAuth(token("1", "apigatewaylab", 3600)))
                .exchange().expectStatus().isEqualTo(502);
    }

    @Test
    void redisDenialReturns429ForRoutesAndDashboard() {
        when(limiter.isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(false, Map.of("X-RateLimit-Remaining", "0"))));
        for (String path : new String[]{"/users/me", "/orders/me", "/dashboard"}) {
            client.get().uri(path).headers(h -> h.setBearerAuth(token("1", "apigatewaylab", 3600)))
                    .exchange().expectStatus().isEqualTo(429).expectHeader().valueEquals("Retry-After", "1");
        }
    }

    @Test
    void redisFailureCannotSilentlyBypassLimiter() {
        when(limiter.isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(true, Map.of("X-RateLimit-Remaining", "-1"))));
        client.get().uri("/users/me").headers(h -> h.setBearerAuth(token("1", "apigatewaylab", 3600)))
                .exchange().expectStatus().isEqualTo(503);
    }

    private static String token(String subject, String issuer, long expiry) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject(subject).issuer(issuer).issuedAt(Instant.now().minusSeconds(1200))
                        .expiresAt(Instant.now().plusSeconds(expiry)).build())).getTokenValue();
    }

    private static HttpServer server(boolean users) {
        try {
            var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                (users ? USER_TOKEN : ORDER_TOKEN).set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                if (users) SPOOFED_USER.set(exchange.getRequestHeaders().getFirst("X-User-ID"));
                boolean validAuth = exchange.getRequestURI().getPath().startsWith("/auth/") ||
                        exchange.getRequestHeaders().getFirst("Authorization") != null;
                int status = validAuth && (users || !FAIL_ORDERS.get()) ? 200 : 503;
                byte[] body = (users ? "{\"id\":1,\"username\":\"huy\",\"email\":\"huy@example.com\"}" :
                        "[{\"id\":1,\"productName\":\"Keyboard\",\"totalAmount\":500000}]").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (var stream = exchange.getResponseBody()) { stream.write(body); }
            });
            server.start();
            return server;
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
}
