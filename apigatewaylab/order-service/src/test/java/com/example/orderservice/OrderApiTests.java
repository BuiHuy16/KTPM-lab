package com.example.orderservice;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import com.example.orderservice.model.Order;
import com.example.orderservice.repository.OrderRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderApiTests {
    static final String INTERNAL = "test-only-internal-secret-at-least-32-chars";
    WebApplicationContext context;
    OrderRepository orders;
    MockMvc mvc;

    static ConfigurableApplicationContext application;
    @BeforeAll
    static void startApplication() {
        application = new SpringApplicationBuilder(OrderServiceApplication.class)
                .profiles("test").run("--server.port=0");
    }
    @AfterAll
    static void stopApplication() { if (application != null) application.close(); }

    @BeforeEach
    void setup() {
        context = (WebApplicationContext) application;
        orders = application.getBean(OrderRepository.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        orders.deleteAll();
    }

    @Test
    void createsOrderForJwtOwnerAndIgnoresForgedIdentity() throws Exception {
        mvc.perform(post("/orders").header("X-Internal-Token", INTERNAL).header("Authorization", "Bearer " + token(1))
                .header("X-User-ID", "99").contentType("application/json")
                .content("{\"productName\":\"Keyboard\",\"quantity\":2,\"totalAmount\":500000,\"userId\":99}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.status").value("CREATED"));
        Assertions.assertEquals(1L, orders.findAll().getFirst().getUserId());
    }

    @Test
    void onlyOwnerCanGetOrderOrSeeItInList() throws Exception {
        var order = orders.saveAndFlush(new Order(1L, "Keyboard", 1, new BigDecimal("500000")));
        orders.saveAndFlush(new Order(2L, "Private product", 1, BigDecimal.ONE));
        mvc.perform(get("/orders/" + order.getId()).header("X-Internal-Token", INTERNAL).header("Authorization", "Bearer " + token(1)))
                .andExpect(status().isOk());
        mvc.perform(get("/orders/" + order.getId()).header("X-Internal-Token", INTERNAL).header("Authorization", "Bearer " + token(2)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/orders/me?userId=2").header("X-Internal-Token", INTERNAL).header("Authorization", "Bearer " + token(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productName").value("Keyboard"));
    }

    @Test
    void rejectsMissingInvalidJwtAndMissingInternalToken() throws Exception {
        mvc.perform(get("/orders/me").header("X-User-ID", "1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/orders/me").header("Authorization", "Bearer " + token(1))).andExpect(status().isUnauthorized());
        mvc.perform(get("/orders/me").header("X-Internal-Token", INTERNAL).header("X-User-ID", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/orders/me").header("X-Internal-Token", INTERNAL).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsNonPositiveQuantityAndAmount() throws Exception {
        mvc.perform(post("/orders").header("X-Internal-Token", INTERNAL).header("Authorization", "Bearer " + token(1))
                .contentType("application/json").content("{\"productName\":\"Keyboard\",\"quantity\":0,\"totalAmount\":-1}"))
                .andExpect(status().isBadRequest());
        Assertions.assertEquals(0, orders.count());
    }

    private static String token(long userId) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "test-only-jwt-signing-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject(Long.toString(userId)).issuer("apigatewaylab")
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build())).getTokenValue();
    }
}
