package com.example.shop.integration;

import com.example.shop.dto.AddressDTO;
import com.example.shop.dto.CustomerRequestDTO;
import com.example.shop.dto.CustomerResponseDTO;
import com.example.shop.dto.OrderRequestDTO;
import com.example.shop.dto.OrderResponseDTO;
import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.idempotency.IdempotencyRecord;
import com.example.shop.repository.CustomerRepository;
import com.example.shop.repository.OrderRepository;
import com.example.shop.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private MongoTemplate mongoTemplate;

    private String customerId;
    private String productId;
    private OrderRequestDTO request;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        customerRepository.deleteAll();
        productRepository.deleteAll();
        mongoTemplate.remove(new Query(), IdempotencyRecord.class);
        Objects.requireNonNull(cacheManager.getCache("products")).clear();

        AddressDTO address = new AddressDTO();
        address.setStreet("1 Test St");
        address.setCity("London");
        address.setCountry("UK");
        address.setZipCode("T1 1TT");
        CustomerRequestDTO customerReq = new CustomerRequestDTO();
        customerReq.setName("Alice");
        customerReq.setEmail("alice@test.com");
        customerReq.setAddress(address);
        customerId = restTemplate
                .postForEntity("/api/customers", customerReq, CustomerResponseDTO.class)
                .getBody().getId();

        ProductRequestDTO productReq = new ProductRequestDTO();
        productReq.setName("Laptop");
        productReq.setDescription("d");
        productReq.setPrice(999.99);
        productReq.setStock(5);
        productId = restTemplate
                .postForEntity("/api/products", productReq, ProductResponseDTO.class)
                .getBody().getId();

        request = new OrderRequestDTO();
        request.setCustomerId(customerId);
        request.setProductIds(List.of(productId));
    }

    @Test
    void placeOrder_withSameIdempotencyKey_deductsStockOnlyOnce() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        HttpEntity<OrderRequestDTO> entity = new HttpEntity<>(request, headers);

        ResponseEntity<OrderResponseDTO> first = restTemplate.exchange(
                "/api/orders/place", HttpMethod.POST, entity, OrderResponseDTO.class);
        ResponseEntity<OrderResponseDTO> second = restTemplate.exchange(
                "/api/orders/place", HttpMethod.POST, entity, OrderResponseDTO.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().getId()).isEqualTo(first.getBody().getId());
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(4);
    }

    @Test
    void placeOrder_withDifferentIdempotencyKeys_createsTwoOrders() {
        HttpEntity<OrderRequestDTO> e1 = new HttpEntity<>(request, headers("key-a"));
        HttpEntity<OrderRequestDTO> e2 = new HttpEntity<>(request, headers("key-b"));

        restTemplate.exchange("/api/orders/place", HttpMethod.POST, e1, OrderResponseDTO.class);
        restTemplate.exchange("/api/orders/place", HttpMethod.POST, e2, OrderResponseDTO.class);

        assertThat(orderRepository.count()).isEqualTo(2);
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(3);
    }

    @Test
    void placeOrder_withoutIdempotencyKey_alwaysCreatesNewOrder() {
        restTemplate.postForEntity("/api/orders/place", request, OrderResponseDTO.class);
        restTemplate.postForEntity("/api/orders/place", request, OrderResponseDTO.class);

        assertThat(orderRepository.count()).isEqualTo(2);
    }

    private HttpHeaders headers(String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("Idempotency-Key", key);
        return h;
    }
}
