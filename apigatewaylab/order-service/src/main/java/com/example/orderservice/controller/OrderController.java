package com.example.orderservice.controller;

import java.math.BigDecimal;
import java.util.List;
import com.example.orderservice.model.Order;
import com.example.orderservice.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderService orders;
    public OrderController(OrderService orders) { this.orders = orders; }
    public record CreateOrder(@NotBlank @Size(max = 200) String productName,
                              @Min(1) @Max(1000000) int quantity,
                              @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal totalAmount) {}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Order create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateOrder request) {
        return orders.create(Long.parseLong(jwt.getSubject()), request.productName(), request.quantity(), request.totalAmount());
    }
    @GetMapping("/me")
    public List<Order> mine(@AuthenticationPrincipal Jwt jwt) { return orders.mine(Long.parseLong(jwt.getSubject())); }
    @GetMapping("/{id}")
    public Order get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return orders.get(id, Long.parseLong(jwt.getSubject()));
    }
}
