package com.example.shop.unit.service;

import com.example.shop.dto.OrderDetailResponseDTO;
import com.example.shop.dto.OrderRequestDTO;
import com.example.shop.dto.OrderResponseDTO;
import com.example.shop.mapper.OrderMapper;
import com.example.shop.model.Order;
import com.example.shop.model.OrderDetail;
import com.example.shop.model.Product;
import com.example.shop.repository.CustomerRepository;
import com.example.shop.repository.OrderRepository;
import com.example.shop.repository.ProductRepository;
import com.example.shop.service.OrderService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private ProductRepository productRepository;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private OrderMapper orderMapper;
    @InjectMocks private OrderService orderService;

    private final ObjectId customerId = new ObjectId();
    private final ObjectId productId  = new ObjectId();

    private Order order;
    private OrderRequestDTO requestDTO;
    private OrderResponseDTO responseDTO;
    private Product product;

    @BeforeEach
    void setUp() {
        product  = new Product(productId.toHexString(), "Laptop", "desc", 999.99, 5);

        order = new Order();
        order.setId(new ObjectId().toHexString());
        order.setCustomerId(customerId);
        order.setProductIds(List.of(productId));
        order.setTotal(999.99);

        requestDTO = new OrderRequestDTO();
        requestDTO.setCustomerId(customerId.toHexString());
        requestDTO.setProductIds(List.of(productId.toHexString()));

        responseDTO = new OrderResponseDTO();
        responseDTO.setId(order.getId());
        responseDTO.setCustomerId(customerId.toHexString());
        responseDTO.setProductIds(List.of(productId.toHexString()));
        responseDTO.setTotal(999.99);
        responseDTO.setStatus(Order.OrderStatus.PENDING);
    }

    @Test
    void findAll_returnsMappedList() {
        when(orderRepository.findAll()).thenReturn(List.of(order));
        when(orderMapper.toResponse(order)).thenReturn(responseDTO);

        assertThat(orderService.findAll()).containsExactly(responseDTO);
    }

    @Test
    void findById_whenFound_returnsMappedDTO() {
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderMapper.toResponse(order)).thenReturn(responseDTO);

        assertThat(orderService.findById(order.getId())).isEqualTo(responseDTO);
    }

    @Test
    void findById_whenNotFound_throwsException() {
        when(orderRepository.findById("bad-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById("bad-id"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("bad-id");
    }

    @Test
    void create_whenValid_computesTotalAndSaves() {
        when(customerRepository.existsById(customerId.toHexString())).thenReturn(true);
        when(productRepository.findAllById(List.of(productId.toHexString()))).thenReturn(List.of(product));
        when(orderMapper.toEntity(requestDTO)).thenReturn(order);
        when(orderRepository.save(order)).thenReturn(order);
        when(orderMapper.toResponse(order)).thenReturn(responseDTO);

        OrderResponseDTO result = orderService.create(requestDTO);

        assertThat(result).isEqualTo(responseDTO);
        assertThat(order.getTotal()).isEqualTo(999.99);
        verify(orderRepository).save(order);
    }

    @Test
    void create_whenCustomerNotFound_throwsException() {
        when(customerRepository.existsById(customerId.toHexString())).thenReturn(false);

        assertThatThrownBy(() -> orderService.create(requestDTO))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Customer not found");
    }

    @Test
    void create_whenProductMissing_throwsException() {
        when(customerRepository.existsById(customerId.toHexString())).thenReturn(true);
        when(productRepository.findAllById(any())).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.create(requestDTO))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("products not found");
    }

    @Test
    void updateStatus_changesStatusAndSaves() {
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(orderMapper.toResponse(order)).thenReturn(responseDTO);

        orderService.updateStatus(order.getId(), Order.OrderStatus.CONFIRMED);

        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        verify(orderRepository).save(order);
    }

    @Test
    void delete_callsDeleteById() {
        orderService.delete("1");
        verify(orderRepository).deleteById("1");
    }

    @Test
    void placeOrder_whenCustomerNotFound_throwsException() {
        when(customerRepository.existsById(customerId.toHexString())).thenReturn(false);

        assertThatThrownBy(() -> orderService.placeOrder(requestDTO, false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Customer not found");
    }

    @Test
    void placeOrder_whenProductMissing_throwsException() {
        when(customerRepository.existsById(customerId.toHexString())).thenReturn(true);
        when(productRepository.findAllById(any())).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.placeOrder(requestDTO, false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("products not found");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findDetailById_returnsMappedDTO() {
        String id = new ObjectId().toHexString();
        OrderDetail detail = new OrderDetail();
        OrderDetailResponseDTO detailDTO = new OrderDetailResponseDTO();

        AggregationResults<OrderDetail> results = mock(AggregationResults.class);
        when(mongoTemplate.aggregate(any(Aggregation.class), eq("orders"), eq(OrderDetail.class)))
                .thenReturn(results);
        when(results.getUniqueMappedResult()).thenReturn(detail);
        when(orderMapper.toDetailResponse(detail)).thenReturn(detailDTO);

        assertThat(orderService.findDetailById(id)).isEqualTo(detailDTO);
    }
}
