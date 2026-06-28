package com.example.shop.unit.service;

import com.example.shop.dto.AddressDTO;
import com.example.shop.dto.CustomerRequestDTO;
import com.example.shop.dto.CustomerResponseDTO;
import com.example.shop.mapper.CustomerMapper;
import com.example.shop.model.Address;
import com.example.shop.model.Customer;
import com.example.shop.repository.CustomerRepository;
import com.example.shop.service.CustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private CustomerMapper customerMapper;
    @InjectMocks private CustomerService customerService;

    private Customer customer;
    private CustomerRequestDTO requestDTO;
    private CustomerResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        AddressDTO addressDTO = new AddressDTO();
        addressDTO.setStreet("123 Main St");
        addressDTO.setCity("London");
        addressDTO.setCountry("UK");
        addressDTO.setZipCode("SW1A");

        customer = new Customer("1", "Alice", "alice@test.com",
                new Address("123 Main St", "London", "UK", "SW1A"));

        requestDTO = new CustomerRequestDTO();
        requestDTO.setName("Alice");
        requestDTO.setEmail("alice@test.com");
        requestDTO.setAddress(addressDTO);

        responseDTO = new CustomerResponseDTO();
        responseDTO.setId("1");
        responseDTO.setName("Alice");
        responseDTO.setEmail("alice@test.com");
        responseDTO.setAddress(addressDTO);
    }

    @Test
    void findAll_returnsMappedList() {
        when(customerRepository.findAll()).thenReturn(List.of(customer));
        when(customerMapper.toResponse(customer)).thenReturn(responseDTO);

        assertThat(customerService.findAll()).containsExactly(responseDTO);
    }

    @Test
    void findById_whenFound_returnsMappedDTO() {
        when(customerRepository.findById("1")).thenReturn(Optional.of(customer));
        when(customerMapper.toResponse(customer)).thenReturn(responseDTO);

        assertThat(customerService.findById("1")).isEqualTo(responseDTO);
    }

    @Test
    void findById_whenNotFound_throwsException() {
        when(customerRepository.findById("99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.findById("99"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("99");
    }

    @Test
    void create_savesAndReturnsMappedDTO() {
        when(customerMapper.toEntity(requestDTO)).thenReturn(customer);
        when(customerRepository.save(customer)).thenReturn(customer);
        when(customerMapper.toResponse(customer)).thenReturn(responseDTO);

        assertThat(customerService.create(requestDTO)).isEqualTo(responseDTO);
        verify(customerRepository).save(customer);
    }

    @Test
    void update_whenFound_updatesAndReturns() {
        when(customerRepository.findById("1")).thenReturn(Optional.of(customer));
        when(customerRepository.save(customer)).thenReturn(customer);
        when(customerMapper.toResponse(customer)).thenReturn(responseDTO);

        assertThat(customerService.update("1", requestDTO)).isEqualTo(responseDTO);
        verify(customerMapper).updateEntity(requestDTO, customer);
        verify(customerRepository).save(customer);
    }

    @Test
    void update_whenNotFound_throwsException() {
        when(customerRepository.findById("99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.update("99", requestDTO))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void delete_callsDeleteById() {
        customerService.delete("1");
        verify(customerRepository).deleteById("1");
    }

    @Test
    void findByAddress_returnsMappedList() {
        when(customerRepository.findByAddressContaining("London")).thenReturn(List.of(customer));
        when(customerMapper.toResponse(customer)).thenReturn(responseDTO);

        assertThat(customerService.findByAddress("London")).containsExactly(responseDTO);
    }

    @Test
    void findByName_returnsMappedList() {
        when(customerRepository.findByNameContainingIgnoreCase("Alice")).thenReturn(List.of(customer));
        when(customerMapper.toResponse(customer)).thenReturn(responseDTO);

        assertThat(customerService.findByName("Alice")).containsExactly(responseDTO);
    }

    @Test
    void findPaged_noCityFilter_returnsAllCustomersPage() {
        Page<Customer> page = new PageImpl<>(List.of(customer));
        when(customerRepository.findAll(any(Pageable.class))).thenReturn(page);
        when(customerMapper.toResponse(customer)).thenReturn(responseDTO);

        Page<CustomerResponseDTO> result = customerService.findPaged(0, 10, "name", "asc", null);

        assertThat(result.getContent()).containsExactly(responseDTO);
    }

    @Test
    void findPaged_withCityFilter_returnsFilteredPage() {
        Page<Customer> page = new PageImpl<>(List.of(customer));
        when(customerRepository.findByAddressCityContainingIgnoreCase(eq("London"), any(Pageable.class))).thenReturn(page);
        when(customerMapper.toResponse(customer)).thenReturn(responseDTO);

        Page<CustomerResponseDTO> result = customerService.findPaged(0, 10, "name", "asc", "London");

        assertThat(result.getContent()).containsExactly(responseDTO);
    }

    @Test
    void findPaged_descSort_returnsPageSortedDescending() {
        Page<Customer> page = new PageImpl<>(List.of(customer));
        when(customerRepository.findAll(any(Pageable.class))).thenReturn(page);
        when(customerMapper.toResponse(customer)).thenReturn(responseDTO);

        Page<CustomerResponseDTO> result = customerService.findPaged(0, 10, "name", "desc", null);

        assertThat(result.getContent()).containsExactly(responseDTO);
    }
}
