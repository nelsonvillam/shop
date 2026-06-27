package com.example.shop.service;

import com.example.shop.dto.OrderDetailResponseDTO;
import com.example.shop.dto.OrderRequestDTO;
import com.example.shop.dto.OrderResponseDTO;
import com.example.shop.mapper.OrderMapper;
import com.example.shop.model.Order;
import com.example.shop.model.OrderDetail;
import com.example.shop.model.Product;
import com.example.shop.repository.CustomerRepository;
import com.example.shop.repository.OrderRepository;
import com.example.shop.repository.ProductRepository;
import com.example.shop.exception.InsufficientStockException;
import com.example.shop.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.lookup;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.unwind;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final MongoTemplate mongoTemplate;
    private final OrderMapper orderMapper;

    public List<OrderResponseDTO> findAll() {
        return orderRepository.findAll().stream()
                .map(orderMapper::toResponse)
                .toList();
    }

    public OrderResponseDTO findById(String id) {
        return orderRepository.findById(id)
                .map(orderMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
    }

    public List<OrderResponseDTO> findByCustomer(String customerId) {
        return orderRepository.findByCustomerId(new ObjectId(customerId)).stream()
                .map(orderMapper::toResponse)
                .toList();
    }

    public List<OrderResponseDTO> findByProduct(String productId) {
        return orderRepository.findByProductIdsContaining(new ObjectId(productId)).stream()
                .map(orderMapper::toResponse)
                .toList();
    }

    public OrderResponseDTO create(OrderRequestDTO dto) {
        if (!customerRepository.existsById(dto.getCustomerId())) {
            throw new ResourceNotFoundException("Customer not found: " + dto.getCustomerId());
        }

        List<Product> products = productRepository.findAllById(dto.getProductIds());
        if (products.size() != dto.getProductIds().size()) {
            throw new ResourceNotFoundException("One or more products not found");
        }

        Order order = orderMapper.toEntity(dto);
        order.setTotal(products.stream().mapToDouble(Product::getPrice).sum());

        return orderMapper.toResponse(orderRepository.save(order));
    }

    public OrderResponseDTO updateStatus(String id, Order.OrderStatus status) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
        order.setStatus(status);
        return orderMapper.toResponse(orderRepository.save(order));
    }

    public void delete(String id) {
        orderRepository.deleteById(id);
    }

    @Transactional
    public OrderResponseDTO placeOrder(OrderRequestDTO dto, boolean simulateFail) {
        if (!customerRepository.existsById(dto.getCustomerId())) {
            throw new ResourceNotFoundException("Customer not found: " + dto.getCustomerId());
        }

        List<Product> products = productRepository.findAllById(dto.getProductIds());
        if (products.size() != dto.getProductIds().size()) {
            throw new ResourceNotFoundException("One or more products not found");
        }

        for (Product product : products) {
            if (product.getStock() < 1) {
                throw new InsufficientStockException("Insufficient stock for product: " + product.getName());
            }
            product.setStock(product.getStock() - 1);
            productRepository.save(product);
        }

        // Simulates a failure AFTER stock has been deducted — proves the transaction rolls back
        if (simulateFail) {
            throw new RuntimeException("Simulated failure — transaction should roll back stock changes");
        }

        Order order = orderMapper.toEntity(dto);
        order.setTotal(products.stream().mapToDouble(Product::getPrice).sum());
        return orderMapper.toResponse(orderRepository.save(order));
    }

    public OrderDetailResponseDTO findDetailById(String id) {
        Aggregation aggregation = newAggregation(
                match(Criteria.where("_id").is(new ObjectId(id))),
                lookup("customers", "customerId", "_id", "customer"),
                unwind("customer"),
                lookup("products", "productIds", "_id", "products")
        );

        OrderDetail detail = mongoTemplate
                .aggregate(aggregation, "orders", OrderDetail.class)
                .getUniqueMappedResult();

        return orderMapper.toDetailResponse(detail);
    }
}
