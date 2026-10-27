package com.example.orderservice.model;

import java.math.BigDecimal;
import jakarta.persistence.*;

@Entity
@Table(name = "orders")
public class Order {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false, length = 200)
    private String productName;
    @Column(nullable = false)
    private int quantity;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;
    @Column(nullable = false, length = 20)
    private String status;

    protected Order() {}
    public Order(long userId, String productName, int quantity, BigDecimal totalAmount) {
        this.userId = userId; this.productName = productName; this.quantity = quantity;
        this.totalAmount = totalAmount; this.status = "CREATED";
    }
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getProductName() { return productName; }
    public int getQuantity() { return quantity; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getStatus() { return status; }
}
