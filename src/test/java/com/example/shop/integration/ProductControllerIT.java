package com.example.shop.integration;

import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ProductRepository productRepository;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
    }

    private ProductRequestDTO buildRequest(String name, double price, int stock) {
        ProductRequestDTO dto = new ProductRequestDTO();
        dto.setName(name);
        dto.setDescription(name + " description");
        dto.setPrice(price);
        dto.setStock(stock);
        return dto;
    }

    @Test
    void createProduct_returns201AndBody() {
        ResponseEntity<ProductResponseDTO> response = restTemplate.postForEntity(
                "/api/products", buildRequest("Laptop", 999.99, 10), ProductResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getId()).isNotBlank();
        assertThat(response.getBody().getName()).isEqualTo("Laptop");
        assertThat(response.getBody().getPrice()).isEqualTo(999.99);
    }

    @Test
    void findAll_returnsAllProducts() {
        restTemplate.postForEntity("/api/products", buildRequest("Laptop", 999.99, 10), ProductResponseDTO.class);
        restTemplate.postForEntity("/api/products", buildRequest("Mouse", 29.99, 50), ProductResponseDTO.class);

        ResponseEntity<List<ProductResponseDTO>> response = restTemplate.exchange(
                "/api/products", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void findById_whenExists_returnsProduct() {
        ProductResponseDTO created = restTemplate.postForEntity(
                "/api/products", buildRequest("Laptop", 999.99, 10), ProductResponseDTO.class).getBody();

        ResponseEntity<ProductResponseDTO> response = restTemplate.getForEntity(
                "/api/products/" + created.getId(), ProductResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getName()).isEqualTo("Laptop");
    }

    @Test
    void search_returnsMatchingProducts() {
        restTemplate.postForEntity("/api/products", buildRequest("Laptop Pro", 999.99, 10), ProductResponseDTO.class);
        restTemplate.postForEntity("/api/products", buildRequest("Mouse", 29.99, 50), ProductResponseDTO.class);

        ResponseEntity<List<ProductResponseDTO>> response = restTemplate.exchange(
                "/api/products?search=laptop", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getName()).isEqualTo("Laptop Pro");
    }

    @Test
    void updateProduct_returnsUpdatedBody() {
        ProductResponseDTO created = restTemplate.postForEntity(
                "/api/products", buildRequest("Laptop", 999.99, 10), ProductResponseDTO.class).getBody();

        ProductRequestDTO updateRequest = buildRequest("Laptop Pro", 1199.99, 8);
        ResponseEntity<ProductResponseDTO> response = restTemplate.exchange(
                "/api/products/" + created.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest),
                ProductResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getName()).isEqualTo("Laptop Pro");
        assertThat(response.getBody().getPrice()).isEqualTo(1199.99);
        assertThat(response.getBody().getStock()).isEqualTo(8);
    }

    @Test
    void deleteProduct_returns204AndRemovesIt() {
        ProductResponseDTO created = restTemplate.postForEntity(
                "/api/products", buildRequest("Laptop", 999.99, 10), ProductResponseDTO.class).getBody();

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/products/" + created.getId(), HttpMethod.DELETE, null, Void.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(productRepository.findById(created.getId())).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Pagination and sorting tests
    // -------------------------------------------------------------------------

    @Test
    void findPaged_returnsCorrectPageSizeAndMetadata() {
        restTemplate.postForEntity("/api/products", buildRequest("Alpha", 10.0, 1), ProductResponseDTO.class);
        restTemplate.postForEntity("/api/products", buildRequest("Beta",  20.0, 2), ProductResponseDTO.class);
        restTemplate.postForEntity("/api/products", buildRequest("Gamma", 30.0, 3), ProductResponseDTO.class);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/products/page?page=0&size=2&sortBy=name&sortDir=asc",
                HttpMethod.GET, null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.get("content").size()).isEqualTo(2);
        assertThat(body.get("totalElements").asInt()).isEqualTo(3);
        assertThat(body.get("totalPages").asInt()).isEqualTo(2);
        assertThat(body.get("first").asBoolean()).isTrue();
        assertThat(body.get("last").asBoolean()).isFalse();
    }

    @Test
    void findPaged_secondPage_returnsRemainingItems() {
        restTemplate.postForEntity("/api/products", buildRequest("Alpha", 10.0, 1), ProductResponseDTO.class);
        restTemplate.postForEntity("/api/products", buildRequest("Beta",  20.0, 2), ProductResponseDTO.class);
        restTemplate.postForEntity("/api/products", buildRequest("Gamma", 30.0, 3), ProductResponseDTO.class);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/products/page?page=1&size=2&sortBy=name&sortDir=asc",
                HttpMethod.GET, null, JsonNode.class);

        JsonNode body = response.getBody();
        assertThat(body.get("content").size()).isEqualTo(1);
        assertThat(body.get("last").asBoolean()).isTrue();
        assertThat(body.get("content").get(0).get("name").asText()).isEqualTo("Gamma");
    }

    @Test
    void findPaged_sortByPriceDesc_returnsProductsInDescendingOrder() {
        restTemplate.postForEntity("/api/products", buildRequest("Cheap",     5.0, 1), ProductResponseDTO.class);
        restTemplate.postForEntity("/api/products", buildRequest("Mid",      50.0, 1), ProductResponseDTO.class);
        restTemplate.postForEntity("/api/products", buildRequest("Expensive", 500.0, 1), ProductResponseDTO.class);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/products/page?page=0&size=10&sortBy=price&sortDir=desc",
                HttpMethod.GET, null, JsonNode.class);

        JsonNode content = response.getBody().get("content");
        assertThat(content.get(0).get("price").asDouble()).isEqualTo(500.0);
        assertThat(content.get(1).get("price").asDouble()).isEqualTo(50.0);
        assertThat(content.get(2).get("price").asDouble()).isEqualTo(5.0);
    }
}
