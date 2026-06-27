package com.example.shop.service;

import com.example.shop.dto.CustomerRequestDTO;
import com.example.shop.dto.CustomerResponseDTO;
import com.example.shop.exception.ResourceNotFoundException;
import com.example.shop.mapper.CustomerMapper;
import com.example.shop.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    public List<CustomerResponseDTO> findAll() {
        return customerRepository.findAll().stream()
                .map(customerMapper::toResponse)
                .toList();
    }

    public CustomerResponseDTO findById(String id) {
        return customerRepository.findById(id)
                .map(customerMapper::toResponse)
                .orElseThrow(() -> {
                    log.warn("Customer not found: {}", id);
                    return new ResourceNotFoundException("Customer not found: " + id);
                });
    }

    public CustomerResponseDTO create(CustomerRequestDTO dto) {
        CustomerResponseDTO created = customerMapper.toResponse(customerRepository.save(customerMapper.toEntity(dto)));
        log.info("Customer created: id={} name={}", created.getId(), created.getName());
        return created;
    }

    public CustomerResponseDTO update(String id, CustomerRequestDTO dto) {
        var existing = customerRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Customer not found for update: {}", id);
                    return new ResourceNotFoundException("Customer not found: " + id);
                });
        customerMapper.updateEntity(dto, existing);
        CustomerResponseDTO updated = customerMapper.toResponse(customerRepository.save(existing));
        log.info("Customer updated: id={}", id);
        return updated;
    }

    public void delete(String id) {
        customerRepository.deleteById(id);
        log.info("Customer deleted: id={}", id);
    }

    public List<CustomerResponseDTO> findByAddress(String keyword) {
        return customerRepository.findByAddressContaining(keyword).stream()
                .map(customerMapper::toResponse)
                .toList();
    }

    public List<CustomerResponseDTO> findByName(String name) {
        return customerRepository.findByNameContainingIgnoreCase(name).stream()
                .map(customerMapper::toResponse)
                .toList();
    }
}
