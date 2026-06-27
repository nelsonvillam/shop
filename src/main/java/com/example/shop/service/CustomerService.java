package com.example.shop.service;

import com.example.shop.dto.CustomerRequestDTO;
import com.example.shop.dto.CustomerResponseDTO;
import com.example.shop.mapper.CustomerMapper;
import com.example.shop.repository.CustomerRepository;
import com.example.shop.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

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
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
    }

    public CustomerResponseDTO create(CustomerRequestDTO dto) {
        return customerMapper.toResponse(customerRepository.save(customerMapper.toEntity(dto)));
    }

    public CustomerResponseDTO update(String id, CustomerRequestDTO dto) {
        var existing = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
        customerMapper.updateEntity(dto, existing);
        return customerMapper.toResponse(customerRepository.save(existing));
    }

    public void delete(String id) {
        customerRepository.deleteById(id);
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
