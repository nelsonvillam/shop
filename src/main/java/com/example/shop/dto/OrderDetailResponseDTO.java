package com.example.shop.dto;

import com.example.shop.model.Order.OrderStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderDetailResponseDTO {
    private String id;
    private OrderStatus status;
    private LocalDateTime createdAt;
    private double total;
    private CustomerResponseDTO customer;
    private List<ProductResponseDTO> products;
}
