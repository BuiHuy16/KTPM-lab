package com.example.userservice.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import com.example.userservice.controller.UserDtos.TokenResponse;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final String issuer;
    private final long lifetime;

    public TokenService(@Value("${lab.jwt.secret}") String secret,
                        @Value("${lab.jwt.issuer}") String issuer,
                        @Value("${lab.jwt.lifetime-seconds}") long lifetime) {
        if (lifetime <= 0) throw new IllegalArgumentException("JWT lifetime must be positive");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        this.issuer = issuer; this.lifetime = lifetime;
    }

    public TokenResponse issue(long userId) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(Long.toString(userId))
                .issuedAt(now).expiresAt(now.plusSeconds(lifetime)).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", lifetime);
    }
}
