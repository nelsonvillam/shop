package com.example.shop.integration;

import com.example.shop.dto.AddressDTO;
import com.example.shop.dto.CustomerRequestDTO;
import com.example.shop.dto.CustomerResponseDTO;
import com.example.shop.repository.CustomerRepository;
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

class CustomerControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private CustomerRepository customerRepository;

    @BeforeEach
    void setUp() {
        customerRepository.deleteAll();
    }

    private CustomerRequestDTO buildRequest(String name, String email, String city) {
        AddressDTO address = new AddressDTO();
        address.setStreet("1 Test St");
        address.setCity(city);
        address.setCountry("UK");
        address.setZipCode("T1 1TT");

        CustomerRequestDTO dto = new CustomerRequestDTO();
        dto.setName(name);
        dto.setEmail(email);
        dto.setAddress(address);
        return dto;
    }

    @Test
    void createCustomer_returns201AndBody() {
        ResponseEntity<CustomerResponseDTO> response = restTemplate.postForEntity(
                "/api/customers", buildRequest("Alice", "alice@test.com", "London"),
                CustomerResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isNotBlank();
        assertThat(response.getBody().getName()).isEqualTo("Alice");
        assertThat(response.getBody().getAddress().getCity()).isEqualTo("London");
    }

    @Test
    void findAll_returnsAllCustomers() {
        restTemplate.postForEntity("/api/customers", buildRequest("Alice", "alice@test.com", "London"), CustomerResponseDTO.class);
        restTemplate.postForEntity("/api/customers", buildRequest("Bob", "bob@test.com", "Paris"), CustomerResponseDTO.class);

        ResponseEntity<List<CustomerResponseDTO>> response = restTemplate.exchange(
                "/api/customers", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void findById_whenExists_returnsCustomer() {
        CustomerResponseDTO created = restTemplate.postForEntity(
                "/api/customers", buildRequest("Alice", "alice@test.com", "London"),
                CustomerResponseDTO.class).getBody();

        ResponseEntity<CustomerResponseDTO> response = restTemplate.getForEntity(
                "/api/customers/" + created.getId(), CustomerResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getName()).isEqualTo("Alice");
    }

    @Test
    void updateCustomer_returnsUpdatedBody() {
        CustomerResponseDTO created = restTemplate.postForEntity(
                "/api/customers", buildRequest("Alice", "alice@test.com", "London"),
                CustomerResponseDTO.class).getBody();

        CustomerRequestDTO updateRequest = buildRequest("Alice Updated", "alice@test.com", "Manchester");
        ResponseEntity<CustomerResponseDTO> response = restTemplate.exchange(
                "/api/customers/" + created.getId(),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest),
                CustomerResponseDTO.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getName()).isEqualTo("Alice Updated");
        assertThat(response.getBody().getAddress().getCity()).isEqualTo("Manchester");
    }

    @Test
    void deleteCustomer_returns204AndRemovesIt() {
        CustomerResponseDTO created = restTemplate.postForEntity(
                "/api/customers", buildRequest("Alice", "alice@test.com", "London"),
                CustomerResponseDTO.class).getBody();

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/customers/" + created.getId(), HttpMethod.DELETE, null, Void.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(customerRepository.findById(created.getId())).isEmpty();
    }

    @Test
    void searchByName_returnsMatchingCustomers() {
        restTemplate.postForEntity("/api/customers", buildRequest("Alice Smith", "alice@test.com", "London"), CustomerResponseDTO.class);
        restTemplate.postForEntity("/api/customers", buildRequest("Bob Jones", "bob@test.com", "Paris"), CustomerResponseDTO.class);

        ResponseEntity<List<CustomerResponseDTO>> response = restTemplate.exchange(
                "/api/customers/search/name?keyword=alice", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getName()).isEqualTo("Alice Smith");
    }

    @Test
    void searchByAddress_returnsMatchingCustomers() {
        restTemplate.postForEntity("/api/customers", buildRequest("Alice", "alice@test.com", "London"), CustomerResponseDTO.class);
        restTemplate.postForEntity("/api/customers", buildRequest("Bob", "bob@test.com", "Paris"), CustomerResponseDTO.class);

        ResponseEntity<List<CustomerResponseDTO>> response = restTemplate.exchange(
                "/api/customers/search/address?keyword=london", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getAddress().getCity()).isEqualTo("London");
    }
}
