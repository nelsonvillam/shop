package com.example.shop.integration;

import com.example.shop.dto.AddressDTO;
import com.example.shop.dto.CustomerRequestDTO;
import com.example.shop.dto.CustomerResponseDTO;
import com.example.shop.dto.OrderChangeEvent;
import com.example.shop.dto.OrderRequestDTO;
import com.example.shop.dto.OrderResponseDTO;
import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.repository.CustomerRepository;
import com.example.shop.repository.OrderRepository;
import com.example.shop.repository.ProductRepository;
import com.example.shop.service.OrderChangeStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OrderChangeStreamIT extends AbstractIntegrationTest {

    @Autowired private OrderChangeStreamService orderChangeStreamService;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CacheManager cacheManager;

    private String customerId;
    private String productId;

    @BeforeEach
    @SuppressWarnings("PMD.EmptyCatchBlock")
    void setUp() throws Exception {
        orderRepository.deleteAll();
        customerRepository.deleteAll();
        productRepository.deleteAll();
        Objects.requireNonNull(cacheManager.getCache("products")).clear();

        // Drain events left over from other IT classes that share this Spring context
        while (orderChangeStreamService.pollEvent(100, TimeUnit.MILLISECONDS) != null) {
            // intentional drain
        }

        // Wait for the change stream listener (started via ApplicationReadyEvent)
        int tries = 50;
        while (!orderChangeStreamService.isListening() && tries-- > 0) {
            Thread.sleep(100);
        }
        assertThat(orderChangeStreamService.isListening())
                .as("Change stream listener must be running before tests").isTrue();

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
    }

    @Test
    void changeStream_emitsInsertEvent_whenOrderIsCreated() throws Exception {
        OrderRequestDTO request = new OrderRequestDTO();
        request.setCustomerId(customerId);
        request.setProductIds(List.of(productId));

        OrderResponseDTO created = restTemplate
                .postForEntity("/api/orders", request, OrderResponseDTO.class).getBody();

        OrderChangeEvent event = orderChangeStreamService.pollEvent(5, TimeUnit.SECONDS);

        assertThat(event).isNotNull();
        assertThat(event.operationType()).isEqualTo("insert");
        assertThat(event.orderId()).isEqualTo(created.getId());
        assertThat(event.status()).isEqualTo("PENDING");
        assertThat(event.total()).isEqualTo(999.99);
    }

    @Test
    void changeStream_emitsUpdateEvent_whenOrderStatusChanges() throws Exception {
        OrderRequestDTO request = new OrderRequestDTO();
        request.setCustomerId(customerId);
        request.setProductIds(List.of(productId));
        OrderResponseDTO created = restTemplate
                .postForEntity("/api/orders", request, OrderResponseDTO.class).getBody();

        // Consume the insert event
        assertThat(orderChangeStreamService.pollEvent(5, TimeUnit.SECONDS))
                .isNotNull();

        restTemplate.exchange("/api/orders/" + created.getId() + "/status?status=CONFIRMED",
                HttpMethod.PATCH, null, OrderResponseDTO.class);

        OrderChangeEvent updateEvent = orderChangeStreamService.pollEvent(5, TimeUnit.SECONDS);

        assertThat(updateEvent).isNotNull();
        assertThat(updateEvent.operationType()).isIn("update", "replace");
        assertThat(updateEvent.orderId()).isEqualTo(created.getId());
        assertThat(updateEvent.status()).isEqualTo("CONFIRMED");
    }
}
