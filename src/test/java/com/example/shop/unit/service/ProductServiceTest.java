package com.example.shop.unit.service;

import com.example.shop.dto.ProductRequestDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.mapper.ProductMapper;
import com.example.shop.model.Product;
import com.example.shop.repository.ProductRepository;
import com.example.shop.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductMapper productMapper;
    @InjectMocks private ProductService productService;

    private Product product;
    private ProductRequestDTO requestDTO;
    private ProductResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        product = new Product("1", "Laptop", "A laptop", 999.99, 10);

        requestDTO = new ProductRequestDTO();
        requestDTO.setName("Laptop");
        requestDTO.setDescription("A laptop");
        requestDTO.setPrice(999.99);
        requestDTO.setStock(10);

        responseDTO = new ProductResponseDTO();
        responseDTO.setId("1");
        responseDTO.setName("Laptop");
        responseDTO.setDescription("A laptop");
        responseDTO.setPrice(999.99);
        responseDTO.setStock(10);
    }

    @Test
    void findAll_returnsMappedList() {
        when(productRepository.findAll()).thenReturn(List.of(product));
        when(productMapper.toResponse(product)).thenReturn(responseDTO);

        assertThat(productService.findAll()).containsExactly(responseDTO);
    }

    @Test
    void findById_whenFound_returnsMappedDTO() {
        when(productRepository.findById("1")).thenReturn(Optional.of(product));
        when(productMapper.toResponse(product)).thenReturn(responseDTO);

        assertThat(productService.findById("1")).isEqualTo(responseDTO);
    }

    @Test
    void findById_whenNotFound_throwsException() {
        when(productRepository.findById("99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.findById("99"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("99");
    }

    @Test
    void search_returnsMappedList() {
        when(productRepository.findByNameContainingIgnoreCase("lap")).thenReturn(List.of(product));
        when(productMapper.toResponse(product)).thenReturn(responseDTO);

        assertThat(productService.search("lap")).containsExactly(responseDTO);
    }

    @Test
    void create_savesAndReturnsMappedDTO() {
        when(productMapper.toEntity(requestDTO)).thenReturn(product);
        when(productRepository.save(product)).thenReturn(product);
        when(productMapper.toResponse(product)).thenReturn(responseDTO);

        assertThat(productService.create(requestDTO)).isEqualTo(responseDTO);
        verify(productRepository).save(product);
    }

    @Test
    void update_whenFound_updatesAndReturns() {
        when(productRepository.findById("1")).thenReturn(Optional.of(product));
        when(productRepository.save(product)).thenReturn(product);
        when(productMapper.toResponse(product)).thenReturn(responseDTO);

        assertThat(productService.update("1", requestDTO)).isEqualTo(responseDTO);
        verify(productMapper).updateEntity(requestDTO, product);
        verify(productRepository).save(product);
    }

    @Test
    void update_whenNotFound_throwsException() {
        when(productRepository.findById("99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.update("99", requestDTO))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void delete_callsDeleteById() {
        productService.delete("1");
        verify(productRepository).deleteById("1");
    }
}
