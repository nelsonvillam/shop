package com.example.shop.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

// ONE-TO-ONE: Customer embeds Address directly (no separate collection needed)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "customers")
public class    Customer {

    @Id
    private String id;

    @NotBlank
    private String name;

    @Email
    @NotBlank
    private String email;

    // ONE-TO-ONE (embedded): address lives inside the customer document
    private Address address;
}
