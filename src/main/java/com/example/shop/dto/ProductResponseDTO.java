package com.example.shop.dto;

import lombok.Data;

@Data
public class ProductResponseDTO {
    private String id;
    private String name;
    private String description;
    private double price;
    private int stock;
}
