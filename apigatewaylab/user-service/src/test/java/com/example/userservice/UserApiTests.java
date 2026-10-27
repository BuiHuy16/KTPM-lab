package com.example.userservice;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import com.example.userservice.repository.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserApiTests {
    static final String INTERNAL = "test-only-internal-secret-at-least-32-chars";
    WebApplicationContext context;
    UserRepository users;
    PasswordEncoder passwords;
    JwtDecoder decoder;
    MockMvc mvc;
    JsonMapper json = JsonMapper.builder().build();

    static ConfigurableApplicationContext application;
    @BeforeAll
    static void startApplication() {
        application = new SpringApplicationBuilder(UserServiceApplication.class)
                .profiles("test").run("--server.port=0");
    }
    @AfterAll
    static void stopApplication() { if (application != null) application.close(); }

    @BeforeEach
    void setup() {
        context = (WebApplicationContext) application;
        users = application.getBean(UserRepository.class);
        passwords = application.getBean(PasswordEncoder.class);
        decoder = application.getBean(JwtDecoder.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        users.deleteAll();
    }

    @Test
    void registrationHashesPasswordLoginIssuesVerifiableExpiringJwtAndMeHidesHash() throws Exception {
        register("huy");
        var user = users.findByUsername("huy").orElseThrow();
        Assertions.assertNotEquals("password123", user.getPasswordHash());
        Assertions.assertTrue(passwords.matches("password123", user.getPasswordHash()));
        String token = login("huy");
        Jwt jwt = decoder.decode(token);
        Assertions.assertEquals(user.getId().toString(), jwt.getSubject());
        Assertions.assertEquals(3600, jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond());
        mvc.perform(get("/users/me").header("X-Internal-Token", INTERNAL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("huy"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void duplicateRegistrationBadPasswordAndUnknownAccountAreRejected() throws Exception {
        register("huy");
        mvc.perform(post("/auth/register").header("X-Internal-Token", INTERNAL).contentType("application/json")
                .content("{\"username\":\"huy\",\"email\":\"huy@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict());
        for (String username : new String[]{"huy", "unknown"}) {
            mvc.perform(post("/auth/login").header("X-Internal-Token", INTERNAL).contentType("application/json")
                    .content("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void directRequestsCannotSpoofIdentityOrBypassInternalAuthentication() throws Exception {
        register("huy");
        String token = login("huy");
        mvc.perform(get("/users/me").header("X-User-ID", "1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me").header("X-Internal-Token", "attacker").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me").header("X-Internal-Token", INTERNAL).header("X-User-ID", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidAndExpiredJwtAreRejected() throws Exception {
        mvc.perform(get("/users/me").header("X-Internal-Token", INTERNAL).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "test-only-jwt-signing-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject("1").issuer("apigatewaylab").issuedAt(Instant.now().minusSeconds(7200))
                        .expiresAt(Instant.now().minusSeconds(600)).build())).getTokenValue();
        mvc.perform(get("/users/me").header("X-Internal-Token", INTERNAL).header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void avatarPresignedPutUrlHas300SecondExpiryPublicHostnameAndPersistsObjectKey() throws Exception {
        register("huy");
        String token = login("huy");
        var response = mvc.perform(post("/users/avatar/upload-url").header("X-Internal-Token", INTERNAL)
                .header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"contentType\":\"image/png\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresIn").value(300))
                .andExpect(jsonPath("$.method").value("PUT")).andReturn().getResponse().getContentAsString();
        var body = json.readTree(response);
        URI url = URI.create(body.get("uploadUrl").asText());
        Assertions.assertEquals("localhost", url.getHost());
        Assertions.assertEquals(9000, url.getPort());
        Assertions.assertTrue(url.getQuery().contains("X-Amz-Expires=300"));
        Assertions.assertTrue(url.getQuery().contains("X-Amz-Signature="));
        Assertions.assertEquals(body.get("objectKey").asText(), users.findByUsername("huy").orElseThrow().getAvatarObjectKey());
        mvc.perform(post("/users/avatar/upload-url").header("X-Internal-Token", INTERNAL)
                .header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"contentType\":\"text/html\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void inputValidationIncludesBcryptUtf8ByteLimit() throws Exception {
        mvc.perform(post("/auth/register").header("X-Internal-Token", INTERNAL).contentType("application/json")
                .content("{\"username\":\"huy\",\"email\":\"bad-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        String password = "á»‡".repeat(30);
        mvc.perform(post("/auth/register").header("X-Internal-Token", INTERNAL).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("username", "huy", "email", "huy@example.com", "password", password))))
                .andExpect(status().isBadRequest());
    }

    private void register(String username) throws Exception {
        mvc.perform(post("/auth/register").header("X-Internal-Token", INTERNAL).contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());
    }
    private String login(String username) throws Exception {
        var result = mvc.perform(post("/auth/login").header("X-Internal-Token", INTERNAL).contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
