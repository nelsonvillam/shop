package com.example.shop.dto;

import lombok.Data;

@Data
public class CustomerResponseDTO {
    private String id;
    private String name;
    private String email;
    private AddressDTO address;
}
