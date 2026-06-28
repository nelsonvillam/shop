package com.example.shop.integration;

import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.exception.ServiceUnavailableException;
import com.example.shop.repository.ProductRepository;
import com.example.shop.service.ProductService;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductResilienceIT extends AbstractIntegrationTest {

    @Autowired private ProductService productService;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired private CacheManager cacheManager;
    @Autowired private ProductRepository productRepository;
    @Autowired private TestRestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        Objects.requireNonNull(cacheManager.getCache("products")).clear();
    }

    @AfterEach
    void resetCircuitBreaker() {
        circuitBreakerRegistry.circuitBreaker("products").reset();
    }

    @Test
    void findAll_whenCircuitOpen_returnsFallbackEmptyList() {
        createProduct("Laptop", 999.99);
        circuitBreakerRegistry.circuitBreaker("products").transitionToOpenState();

        List<ProductResponseDTO> result = productService.findAll();

        assertThat(result).isEmpty();
        assertThat(circuitBreakerRegistry.circuitBreaker("products").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void findById_whenCircuitOpen_throwsServiceUnavailableException() {
        ProductResponseDTO product = createProduct("Mouse", 29.99);
        circuitBreakerRegistry.circuitBreaker("products").transitionToOpenState();

        assertThatThrownBy(() -> productService.findById(product.getId()))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("temporarily unavailable");
    }

    @Test
    void findAll_whenCircuitOpen_viaHttp_returns200WithEmptyContent() {
        circuitBreakerRegistry.circuitBreaker("products").transitionToOpenState();

        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/api/products", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().isArray()).isTrue();
        assertThat(response.getBody().size()).isZero();
    }

    @Test
    void findById_whenCircuitOpen_viaHttp_returns503() {
        ProductResponseDTO product = createProduct("Tablet", 499.99);
        circuitBreakerRegistry.circuitBreaker("products").transitionToOpenState();

        ResponseEntity<Void> response = restTemplate.getForEntity(
                "/api/products/" + product.getId(), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void findAll_withCircuitClosed_returnsNormalResults() {
        createProduct("Keyboard", 79.99);

        List<ProductResponseDTO> result = productService.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Keyboard");
    }

    private ProductResponseDTO createProduct(String name, double price) {
        ProductRequestDTO dto = new ProductRequestDTO();
        dto.setName(name);
        dto.setDescription(name + " description");
        dto.setPrice(price);
        dto.setStock(10);
        return restTemplate.postForEntity("/api/products", dto, ProductResponseDTO.class).getBody();
    }
}
