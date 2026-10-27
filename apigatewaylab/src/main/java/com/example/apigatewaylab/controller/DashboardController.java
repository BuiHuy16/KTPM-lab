package com.example.apigatewaylab.controller;

import com.example.apigatewaylab.service.DashboardService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
public class DashboardController {
    private final DashboardService dashboards;
    public DashboardController(DashboardService dashboards) { this.dashboards = dashboards; }

    @GetMapping("/dashboard")
    public Mono<DashboardService.Dashboard> dashboard(@AuthenticationPrincipal Jwt jwt) {
        return dashboards.load(jwt);
    }
}
