package com.example.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
@Schema(description = "Payload for creating or updating a product")
public class ProductRequestDTO {

    @NotBlank
    @Schema(description = "Product name", example = "Laptop")
    private String name;

    @Schema(description = "Optional product description", example = "Gaming laptop 16GB RAM")
    private String description;

    @Positive
    @Schema(description = "Price in USD", example = "1299.99")
    private double price;

    @PositiveOrZero
    @Schema(description = "Units available in stock", example = "10")
    private int stock;
}
