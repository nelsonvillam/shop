package com.example.shop.repository;

import com.example.shop.model.Order;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OrderRepository extends MongoRepository<Order, String> {
    // ONE-TO-MANY: find all orders for a given customer
    List<Order> findByCustomerId(ObjectId customerId);

    // MANY-TO-MANY: find all orders that contain a specific product
    List<Order> findByProductIdsContaining(ObjectId productId);

    Page<Order> findByStatus(Order.OrderStatus status, Pageable pageable);
}
