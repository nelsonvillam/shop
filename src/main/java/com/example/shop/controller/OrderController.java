package com.example.shop.controller;

import com.example.shop.dto.OrderDetailResponseDTO;
import com.example.shop.dto.OrderRequestDTO;
import com.example.shop.dto.OrderResponseDTO;
import com.example.shop.metrics.TrackCall;
import com.example.shop.model.Order;
import com.example.shop.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Manage orders")
public class OrderController {

    private final OrderService orderService;

    @TrackCall
    @GetMapping
    @Operation(summary = "List all orders")
    public List<OrderResponseDTO> findAll() {
        return orderService.findAll();
    }

    @TrackCall
    @GetMapping("/customer/{customerId}")
    @Operation(summary = "List orders by customer")
    public List<OrderResponseDTO> findByCustomer(@PathVariable String customerId) {
        return orderService.findByCustomer(customerId);
    }

    @TrackCall
    @GetMapping("/product/{productId}")
    @Operation(summary = "List orders that contain a specific product")
    public List<OrderResponseDTO> findByProduct(@PathVariable String productId) {
        return orderService.findByProduct(productId);
    }

    @TrackCall
    @GetMapping("/{id}")
    @Operation(summary = "Get order by ID")
    @ApiResponse(responseCode = "404", description = "Order not found")
    public OrderResponseDTO findById(@PathVariable String id) {
        return orderService.findById(id);
    }

    @TrackCall
    @GetMapping("/{id}/detail")
    @Operation(summary = "Get order with full customer and product details")
    @ApiResponse(responseCode = "404", description = "Order not found")
    public OrderDetailResponseDTO findDetail(@PathVariable String id) {
        return orderService.findDetailById(id);
    }

    @TrackCall
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new order")
    @ApiResponse(responseCode = "201", description = "Order created")
    @ApiResponse(responseCode = "400", description = "Validation error")
    public OrderResponseDTO create(@Valid @RequestBody OrderRequestDTO dto) {
        return orderService.create(dto);
    }

    @TrackCall
    @PostMapping("/place")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Place an order with stock deduction (transaction demo)",
        description = "Atomically deducts stock from each product and creates the order inside a MongoDB transaction. " +
                      "Set simulateFail=true to force a failure after stock deduction — the transaction rolls back and stock is restored."
    )
    @ApiResponse(responseCode = "201", description = "Order placed and stock updated atomically")
    @ApiResponse(responseCode = "500", description = "Simulated failure — stock rolled back")
    public OrderResponseDTO place(
            @Valid @RequestBody OrderRequestDTO dto,
            @RequestParam(defaultValue = "false") boolean simulateFail) {
        return orderService.placeOrder(dto, simulateFail);
    }

    @TrackCall
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update order status",
               description = "Valid transitions: PENDING → CONFIRMED → SHIPPED → DELIVERED. CANCELLED is always allowed.")
    @ApiResponse(responseCode = "404", description = "Order not found")
    public OrderResponseDTO updateStatus(
            @PathVariable String id,
            @Parameter(description = "New status: PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED")
            @RequestParam Order.OrderStatus status) {
        return orderService.updateStatus(id, status);
    }

    @TrackCall
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an order")
    @ApiResponse(responseCode = "204", description = "Order deleted")
    @ApiResponse(responseCode = "404", description = "Order not found")
    public void delete(@PathVariable String id) {
        orderService.delete(id);
    }
}
