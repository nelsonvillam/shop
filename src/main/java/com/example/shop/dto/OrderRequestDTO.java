package com.example.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Payload for creating an order")
public class OrderRequestDTO {

    @NotBlank
    @Schema(description = "ID of the customer placing the order", example = "64b1f2c3a4e5d60012345678")
    private String customerId;

    @NotEmpty
    @Schema(description = "List of product IDs to include in the order", example = "[\"64b1f2c3a4e5d60012345679\"]")
    private List<String> productIds;
}
