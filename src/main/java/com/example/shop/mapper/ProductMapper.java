package com.example.shop.mapper;

import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.model.Product;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    ProductResponseDTO toResponse(Product product);

    Product toEntity(ProductRequestDTO dto);

    void updateEntity(ProductRequestDTO dto, @MappingTarget Product product);
}
