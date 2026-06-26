package com.example.shop.controller;

import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Manage the product catalog")
public class ProductController {

    private final ProductService productService;

    @GetMapping
    @Operation(summary = "List all products", description = "Returns all products, or filters by name/description when ?search is provided")
    public List<ProductResponseDTO> findAll(
            @Parameter(description = "Optional keyword to filter products by name or description")
            @RequestParam(required = false) String search) {
        if (search != null && !search.isBlank()) {
            return productService.search(search);
        }
        return productService.findAll();
    }

    @GetMapping("/page")
    @Operation(
        summary = "List products with pagination and sorting",
        description = "Returns a page of products. Sort fields: name, price, stock. Sort direction: asc, desc."
    )
    public Page<ProductResponseDTO> findPaged(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size")             @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "Field to sort by")      @RequestParam(defaultValue = "name") String sortBy,
            @Parameter(description = "Sort direction")        @RequestParam(defaultValue = "asc") String sortDir) {
        return productService.findPaged(page, size, sortBy, sortDir);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product by ID")
    @ApiResponse(responseCode = "404", description = "Product not found")
    public ProductResponseDTO findById(@PathVariable String id) {
        return productService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new product")
    @ApiResponse(responseCode = "201", description = "Product created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    public ProductResponseDTO create(@Valid @RequestBody ProductRequestDTO dto) {
        return productService.create(dto);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update an existing product")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "400", description = "Validation error")
    public ProductResponseDTO update(@PathVariable String id, @Valid @RequestBody ProductRequestDTO dto) {
        return productService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a product")
    @ApiResponse(responseCode = "204", description = "Product deleted")
    @ApiResponse(responseCode = "404", description = "Product not found")
    public void delete(@PathVariable String id) {
        productService.delete(id);
    }
}