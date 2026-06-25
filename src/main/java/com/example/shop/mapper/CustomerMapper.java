package com.example.shop.mapper;

import com.example.shop.dto.CustomerRequestDTO;
import com.example.shop.dto.CustomerResponseDTO;
import com.example.shop.model.Customer;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface CustomerMapper {

    CustomerResponseDTO toResponse(Customer customer);

    Customer toEntity(CustomerRequestDTO dto);

    void updateEntity(CustomerRequestDTO dto, @MappingTarget Customer customer);
}
