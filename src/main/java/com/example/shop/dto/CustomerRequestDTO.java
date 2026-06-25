package com.example.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Payload for creating or updating a customer")
public class CustomerRequestDTO {

    @NotBlank
    @Schema(description = "Full name", example = "Alice Smith")
    private String name;

    @Email
    @NotBlank
    @Schema(description = "Valid email address", example = "alice@example.com")
    private String email;

    @Schema(description = "Optional address")
    private AddressDTO address;
}
