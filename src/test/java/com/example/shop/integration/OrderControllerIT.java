package com.example.shop.integration;

import com.example.shop.dto.AddressDTO;
import com.example.shop.dto.CustomerRequestDTO;
import com.example.shop.dto.CustomerResponseDTO;
import com.example.shop.dto.OrderDetailResponseDTO;
import com.example.shop.dto.OrderRequestDTO;
import com.example.shop.dto.OrderResponseDTO;
import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.model.Order;
import com.example.shop.repository.CustomerRepository;
import com.example.shop.repository.OrderRepository;
import com.example.shop.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private ProductRepository productRepository;

    private String customerId;
    private String productId1;
    private String productId2;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        customerRepository.deleteAll();
        productRepository.deleteAll();

        AddressDTO address = new AddressDTO();
        address.setStreet("1 Test St");
        address.setCity("London");
        address.setCountry("UK");
        address.setZipCode("T1 1TT");
        CustomerRequestDTO customerRequest = new CustomerRequestDTO();
        customerRequest.setName("Alice");
        customerRequest.setEmail("alice@test.com");
        customerRequest.setAddress(address);
        customerId = restTemplate.postForEntity("/api/customers", customerRequest, CustomerResponseDTO.class)
                .getBody().getId();

        ProductRequestDTO p1 = new ProductRequestDTO();
        p1.setName("Laptop"); p1.setDescription("d"); p1.setPrice(1000.0); p1.setStock(5);
        productId1 = restTemplate.postForEntity("/api/products", p1, ProductResponseDTO.class).getBody().getId();

        ProductRequestDTO p2 = new ProductRequestDTO();
        p2.setName("Mouse"); p2.setDescription("d"); p2.setPrice(50.0); p2.setStock(20);
        productId2 = restTemplate.postForEntity("/api/products", p2, ProductResponseDTO.class).getBody().getId();
    }

    private OrderRequestDTO buildRequest(String customerId, List<String> productIds) {
        OrderRequestDTO dto = new OrderRequestDTO();
        dto.setCustomerId(customerId);
        dto.setProductIds(productIds);
        return dto;
    }

    @Test
    void createOrder_returns201WithComputedTotal() {
        ResponseEntity<OrderResponseDTO> response = restTemplate.postForEntity(
                "/api/orders", buildRequest(customerId, List.of(productId1, productId2)),
                OrderResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getId()).isNotBlank();
        assertThat(response.getBody().getTotal()).isEqualTo(1050.0);
        assertThat(response.getBody().getStatus()).isEqualTo(Order.OrderStatus.PENDING);
        assertThat(response.getBody().getProductIds()).containsExactlyInAnyOrder(productId1, productId2);
    }

    @Test
    void findAll_returnsAllOrders() {
        restTemplate.postForEntity("/api/orders", buildRequest(customerId, List.of(productId1)), OrderResponseDTO.class);
        restTemplate.postForEntity("/api/orders", buildRequest(customerId, List.of(productId2)), OrderResponseDTO.class);

        ResponseEntity<List<OrderResponseDTO>> response = restTemplate.exchange(
                "/api/orders", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void findById_whenExists_returnsOrder() {
        OrderResponseDTO created = restTemplate.postForEntity(
                "/api/orders", buildRequest(customerId, List.of(productId1)),
                OrderResponseDTO.class).getBody();

        ResponseEntity<OrderResponseDTO> response = restTemplate.getForEntity(
                "/api/orders/" + created.getId(), OrderResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getId()).isEqualTo(created.getId());
    }

    @Test
    void findDetail_returnsNestedCustomerAndProducts() {
        OrderResponseDTO created = restTemplate.postForEntity(
                "/api/orders", buildRequest(customerId, List.of(productId1, productId2)),
                OrderResponseDTO.class).getBody();

        ResponseEntity<OrderDetailResponseDTO> response = restTemplate.getForEntity(
                "/api/orders/" + created.getId() + "/detail", OrderDetailResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getCustomer().getName()).isEqualTo("Alice");
        assertThat(response.getBody().getProducts()).hasSize(2);
        assertThat(response.getBody().getTotal()).isEqualTo(1050.0);
    }

    @Test
    void updateStatus_changesOrderStatus() {
        OrderResponseDTO created = restTemplate.postForEntity(
                "/api/orders", buildRequest(customerId, List.of(productId1)),
                OrderResponseDTO.class).getBody();

        ResponseEntity<OrderResponseDTO> response = restTemplate.exchange(
                "/api/orders/" + created.getId() + "/status?status=CONFIRMED",
                HttpMethod.PATCH, null, OrderResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
    }

    @Test
    void findByCustomer_returnsOnlyThatCustomersOrders() {
        restTemplate.postForEntity("/api/orders", buildRequest(customerId, List.of(productId1)), OrderResponseDTO.class);
        restTemplate.postForEntity("/api/orders", buildRequest(customerId, List.of(productId2)), OrderResponseDTO.class);

        ResponseEntity<List<OrderResponseDTO>> response = restTemplate.exchange(
                "/api/orders/customer/" + customerId, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody()).allMatch(o -> o.getCustomerId().equals(customerId));
    }

    @Test
    void findByProduct_returnsOrdersContainingProduct() {
        restTemplate.postForEntity("/api/orders", buildRequest(customerId, List.of(productId1)), OrderResponseDTO.class);
        restTemplate.postForEntity("/api/orders", buildRequest(customerId, List.of(productId2)), OrderResponseDTO.class);

        ResponseEntity<List<OrderResponseDTO>> response = restTemplate.exchange(
                "/api/orders/product/" + productId1, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getProductIds()).contains(productId1);
    }

    @Test
    void deleteOrder_returns204AndRemovesIt() {
        OrderResponseDTO created = restTemplate.postForEntity(
                "/api/orders", buildRequest(customerId, List.of(productId1)),
                OrderResponseDTO.class).getBody();

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/orders/" + created.getId(), HttpMethod.DELETE, null, Void.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(orderRepository.findById(created.getId())).isEmpty();
    }

    @Test
    void createOrder_withInvalidCustomer_returns404() {
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/orders", buildRequest("000000000000000000000000", List.of(productId1)),
                Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // Transaction (placeOrder) tests
    // -------------------------------------------------------------------------

    @Test
    void placeOrder_success_decrementsStockAndCreatesOrder() {
        int stockBefore = productRepository.findById(productId1).get().getStock(); // 5

        ResponseEntity<OrderResponseDTO> response = restTemplate.postForEntity(
                "/api/orders/place?simulateFail=false",
                buildRequest(customerId, List.of(productId1)),
                OrderResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getId()).isNotBlank();
        assertThat(orderRepository.findById(response.getBody().getId())).isPresent();

        int stockAfter = productRepository.findById(productId1).get().getStock();
        assertThat(stockAfter).isEqualTo(stockBefore - 1);
    }

    @Test
    void placeOrder_withSimulatedFail_rollsBackStockAndDoesNotCreateOrder() {
        int stockBefore = productRepository.findById(productId1).get().getStock(); // 5
        long ordersBefore = orderRepository.count();

        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/orders/place?simulateFail=true",
                buildRequest(customerId, List.of(productId1)),
                Void.class);

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        // Stock must be unchanged — proves the transaction rolled back
        assertThat(productRepository.findById(productId1).get().getStock()).isEqualTo(stockBefore);
        // No order must have been persisted
        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
    }

    // -------------------------------------------------------------------------
    // Pagination tests
    // -------------------------------------------------------------------------

    @Test
    void findPaged_returnsCorrectPageSizeAndMetadata() {
        restTemplate.postForEntity("/api/orders", buildRequest(customerId, List.of(productId1)), OrderResponseDTO.class);
        restTemplate.postForEntity("/api/orders", buildRequest(customerId, List.of(productId2)), OrderResponseDTO.class);
        restTemplate.postForEntity("/api/orders", buildRequest(customerId, List.of(productId1)), OrderResponseDTO.class);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/orders/page?page=0&size=2&sortBy=total&sortDir=desc",
                HttpMethod.GET, null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("content").size()).isEqualTo(2);
        assertThat(body.get("totalElements").asInt()).isEqualTo(3);
        assertThat(body.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void findPaged_withStatusFilter_returnsOnlyMatchingOrders() {
        OrderResponseDTO created = restTemplate.postForEntity(
                "/api/orders", buildRequest(customerId, List.of(productId1)), OrderResponseDTO.class).getBody();
        restTemplate.postForEntity("/api/orders", buildRequest(customerId, List.of(productId2)), OrderResponseDTO.class);

        restTemplate.exchange("/api/orders/" + created.getId() + "/status?status=CONFIRMED",
                HttpMethod.PATCH, null, OrderResponseDTO.class);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/orders/page?status=CONFIRMED",
                HttpMethod.GET, null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("totalElements").asInt()).isEqualTo(1);
        assertThat(response.getBody().get("content").get(0).get("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void placeOrder_withZeroStock_returns409AndDoesNotCreateOrder() {
        // Create a product with no stock
        ProductRequestDTO outOfStock = new ProductRequestDTO();
        outOfStock.setName("SoldOut"); outOfStock.setDescription("d"); outOfStock.setPrice(9.99); outOfStock.setStock(0);
        String outOfStockId = restTemplate.postForEntity("/api/products", outOfStock, ProductResponseDTO.class)
                .getBody().getId();
        long ordersBefore = orderRepository.count();

        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/orders/place?simulateFail=false",
                buildRequest(customerId, List.of(outOfStockId)),
                Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
    }
}
