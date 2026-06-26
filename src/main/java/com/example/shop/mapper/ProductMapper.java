package com.example.shop.mapper;

import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.model.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    ProductResponseDTO toResponse(Product product);

    @Mapping(target = "id", ignore = true)
    Product toEntity(ProductRequestDTO dto);

    @Mapping(target = "id", ignore = true)
    void updateEntity(ProductRequestDTO dto, @MappingTarget Product product);
}
