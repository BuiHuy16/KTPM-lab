package com.example.orderservice.repository;

import com.example.orderservice.model.Order;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByUserIdOrderByIdDesc(long userId);
    Optional<Order> findByIdAndUserId(long id, long userId);
}
