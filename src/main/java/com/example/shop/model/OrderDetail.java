package com.example.shop.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

// Read-only DTO built from the $lookup aggregation result
@Data
public class OrderDetail {
    private String id;
    private Order.OrderStatus status;
    private LocalDateTime createdAt;
    private double total;
    private Customer customer;
    private List<Product> products;
}
