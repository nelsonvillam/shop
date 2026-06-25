package com.example.shop.mapper;

import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.model.Product;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-22T18:16:08-0400",
    comments = "version: 1.5.5.Final, compiler: javac, environment: Java 21.0.11 (Homebrew)"
)
@Component
public class ProductMapperImpl implements ProductMapper {

    @Override
    public ProductResponseDTO toResponse(Product product) {
        if ( product == null ) {
            return null;
        }

        ProductResponseDTO productResponseDTO = new ProductResponseDTO();

        productResponseDTO.setId( product.getId() );
        productResponseDTO.setName( product.getName() );
        productResponseDTO.setDescription( product.getDescription() );
        productResponseDTO.setPrice( product.getPrice() );
        productResponseDTO.setStock( product.getStock() );

        return productResponseDTO;
    }

    @Override
    public Product toEntity(ProductRequestDTO dto) {
        if ( dto == null ) {
            return null;
        }

        Product product = new Product();

        product.setName( dto.getName() );
        product.setDescription( dto.getDescription() );
        product.setPrice( dto.getPrice() );
        product.setStock( dto.getStock() );

        return product;
    }

    @Override
    public void updateEntity(ProductRequestDTO dto, Product product) {
        if ( dto == null ) {
            return;
        }

        product.setName( dto.getName() );
        product.setDescription( dto.getDescription() );
        product.setPrice( dto.getPrice() );
        product.setStock( dto.getStock() );
    }
}
