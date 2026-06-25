package com.example.shop.dto;

import com.example.shop.model.Order.OrderStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderResponseDTO {
    private String id;
    private String customerId;
    private List<String> productIds;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private double total;
}
