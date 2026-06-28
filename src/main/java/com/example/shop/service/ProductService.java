package com.example.shop.service;

import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.exception.ResourceNotFoundException;
import com.example.shop.exception.ServiceUnavailableException;
import com.example.shop.mapper.ProductMapper;
import com.example.shop.repository.ProductRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    @CircuitBreaker(name = "products", fallbackMethod = "findAllFallback")
    @Cacheable("products")
    public List<ProductResponseDTO> findAll() {
        // collect(toList()) returns ArrayList, which Jackson can deserialize from Redis.
        // Stream.toList() returns ImmutableCollections$ListN — a package-private class
        // Jackson cannot instantiate, causing SerializationException on cache read.
        return productRepository.findAll().stream()
                .map(productMapper::toResponse)
                .collect(Collectors.toList());
    }

    List<ProductResponseDTO> findAllFallback(Throwable t) {
        log.warn("Products circuit open — returning empty list: {}", t.getMessage());
        return Collections.emptyList();
    }

    @CircuitBreaker(name = "products", fallbackMethod = "findByIdFallback")
    @Cacheable(value = "products", key = "#id")
    public ProductResponseDTO findById(String id) {
        return productRepository.findById(id)
                .map(productMapper::toResponse)
                .orElseThrow(() -> {
                    log.warn("Product not found: {}", id);
                    return new ResourceNotFoundException("Product not found: " + id);
                });
    }

    ProductResponseDTO findByIdFallback(String id, Throwable t) {
        log.warn("Products circuit open for id={}: {}", id, t.getMessage());
        throw new ServiceUnavailableException("Product service temporarily unavailable: " + id, t);
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
        ProductResponseDTO created = productMapper.toResponse(productRepository.save(productMapper.toEntity(dto)));
        log.info("Product created: id={} name={}", created.getId(), created.getName());
        return created;
    }

    @CacheEvict(value = "products", allEntries = true)
    public ProductResponseDTO update(String id, ProductRequestDTO dto) {
        var existing = productRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Product not found for update: {}", id);
                    return new ResourceNotFoundException("Product not found: " + id);
                });
        productMapper.updateEntity(dto, existing);
        ProductResponseDTO updated = productMapper.toResponse(productRepository.save(existing));
        log.info("Product updated: id={}", id);
        return updated;
    }

    @CacheEvict(value = "products", allEntries = true)
    public void delete(String id) {
        productRepository.deleteById(id);
        log.info("Product deleted: id={}", id);
    }
}
