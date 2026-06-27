package com.example.shop.controller;

import com.example.shop.dto.CustomerRequestDTO;
import com.example.shop.dto.CustomerResponseDTO;
import com.example.shop.metrics.TrackCall;
import com.example.shop.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
@Tag(name = "Customers", description = "Manage customers")
public class CustomerController {

    private final CustomerService customerService;

    @TrackCall
    @GetMapping
    @Operation(summary = "List all customers")
    public List<CustomerResponseDTO> findAll() {
        return customerService.findAll();
    }

    @TrackCall
    @GetMapping("/{id}")
    @Operation(summary = "Get customer by ID")
    @ApiResponse(responseCode = "404", description = "Customer not found")
    public CustomerResponseDTO findById(@PathVariable String id) {
        return customerService.findById(id);
    }

    @TrackCall
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new customer")
    @ApiResponse(responseCode = "201", description = "Customer created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    public CustomerResponseDTO create(@Valid @RequestBody CustomerRequestDTO dto) {
        return customerService.create(dto);
    }

    @TrackCall
    @PutMapping("/{id}")
    @Operation(summary = "Update an existing customer")
    @ApiResponse(responseCode = "404", description = "Customer not found")
    @ApiResponse(responseCode = "400", description = "Validation error")
    public CustomerResponseDTO update(@PathVariable String id, @Valid @RequestBody CustomerRequestDTO dto) {
        return customerService.update(id, dto);
    }

    @TrackCall
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a customer")
    @ApiResponse(responseCode = "204", description = "Customer deleted")
    @ApiResponse(responseCode = "404", description = "Customer not found")
    public void delete(@PathVariable String id) {
        customerService.delete(id);
    }

    @TrackCall
    @GetMapping("/search/address")
    @Operation(summary = "Search customers by address keyword")
    public List<CustomerResponseDTO> findByAddress(
            @Parameter(description = "Keyword to search in city, street or country")
            @RequestParam String keyword) {
        return customerService.findByAddress(keyword);
    }

    @TrackCall
    @GetMapping("/search/name")
    @Operation(summary = "Search customers by name keyword")
    public List<CustomerResponseDTO> findByName(
            @Parameter(description = "Keyword to search in customer name")
            @RequestParam String keyword) {
        return customerService.findByName(keyword);
    }
}
