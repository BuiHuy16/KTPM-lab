package com.example.orderservice.service;

import java.math.BigDecimal;
import java.util.List;
import com.example.orderservice.model.Order;
import com.example.orderservice.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {
    private final OrderRepository orders;
    public OrderService(OrderRepository orders) { this.orders = orders; }
    @Transactional
    public Order create(long userId, String product, int quantity, BigDecimal amount) {
        return orders.save(new Order(userId, product, quantity, amount));
    }
    @Transactional(readOnly = true)
    public List<Order> mine(long userId) { return orders.findByUserIdOrderByIdDesc(userId); }
    @Transactional(readOnly = true)
    public Order get(long id, long userId) {
        return orders.findByIdAndUserId(id, userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }
}
