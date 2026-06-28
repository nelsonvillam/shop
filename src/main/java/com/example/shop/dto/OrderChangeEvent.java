package com.example.shop.dto;

public record OrderChangeEvent(
        String operationType,
        String orderId,
        String customerId,
        String status,
        Double total
) {}
