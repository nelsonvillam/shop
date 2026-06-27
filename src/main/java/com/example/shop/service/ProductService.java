package com.example.shop.service;

import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.mapper.ProductMapper;
import com.example.shop.repository.ProductRepository;
import com.example.shop.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    @Cacheable("products")
    public List<ProductResponseDTO> findAll() {
        // collect(toList()) returns ArrayList, which Jackson can deserialize from Redis.
        // Stream.toList() returns ImmutableCollections$ListN — a package-private class
        // Jackson cannot instantiate, causing SerializationException on cache read.
        return productRepository.findAll().stream()
                .map(productMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Cacheable(value = "products", key = "#id")
    public ProductResponseDTO findById(String id) {
        return productRepository.findById(id)
                .map(productMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    public List<ProductResponseDTO> search(String name) {
        return productRepository.findByNameContainingIgnoreCase(name).stream()
                .map(productMapper::toResponse)
                .toList();
    }

    public Page<ProductResponseDTO> findPaged(int page, int size, String sortBy, String sortDir) {
        Sort sort = "desc".equalsIgnoreCase(sortDir)
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();
        return productRepository.findAll(PageRequest.of(page, size, sort))
                .map(productMapper::toResponse);
    }

    @CacheEvict(value = "products", allEntries = true)
    public ProductResponseDTO create(ProductRequestDTO dto) {
        return productMapper.toResponse(productRepository.save(productMapper.toEntity(dto)));
    }

    @CacheEvict(value = "products", allEntries = true)
    public ProductResponseDTO update(String id, ProductRequestDTO dto) {
        var existing = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
        productMapper.updateEntity(dto, existing);
        return productMapper.toResponse(productRepository.save(existing));
    }

    @CacheEvict(value = "products", allEntries = true)
    public void delete(String id) {
        productRepository.deleteById(id);
    }
}
