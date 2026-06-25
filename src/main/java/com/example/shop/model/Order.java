package com.example.shop.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

// ONE-TO-MANY  : customerId references the Customer collection
// MANY-TO-MANY : productIds references the Products collection
//                (one order has many products, one product appears in many orders)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "orders")
public class Order {

    @Id
    private String id;

    // ONE-TO-MANY side: each order belongs to one customer
    @NotNull
    @Indexed
    private ObjectId customerId;

    // MANY-TO-MANY side: an order holds references to many products
    // MongoDB automatically creates a multikey index when the field is an array
    @NotEmpty
    @Indexed
    private List<ObjectId> productIds;

    private OrderStatus status = OrderStatus.PENDING;

    private LocalDateTime createdAt = LocalDateTime.now();

    private double total;

    public enum OrderStatus {
        PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
    }
}
