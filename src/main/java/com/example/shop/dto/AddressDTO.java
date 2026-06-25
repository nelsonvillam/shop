package com.example.shop.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Customer address")
public class AddressDTO {

    @Schema(example = "123 Main St")
    private String street;

    @Schema(example = "New York")
    private String city;

    @Schema(example = "US")
    private String country;

    @Schema(example = "10001")
    private String zipCode;
}
