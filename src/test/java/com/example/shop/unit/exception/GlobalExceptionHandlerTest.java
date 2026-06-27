package com.example.shop.unit.exception;

import com.example.shop.dto.ErrorResponse;
import com.example.shop.dto.ValidationErrorResponse;
import com.example.shop.exception.GlobalExceptionHandler;
import com.example.shop.exception.InsufficientStockException;
import com.example.shop.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleNotFound_returns404WithMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleNotFound(new ResourceNotFoundException("Product not found: abc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().error()).isEqualTo("Not Found");
        assertThat(response.getBody().message()).isEqualTo("Product not found: abc");
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    void handleInsufficientStock_returns409WithMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleInsufficientStock(new InsufficientStockException("Insufficient stock for product: Laptop"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().error()).isEqualTo("Conflict");
        assertThat(response.getBody().message()).isEqualTo("Insufficient stock for product: Laptop");
    }

    @Test
    void handleRuntimeException_returns500WithMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleRuntimeException(new RuntimeException("unexpected error"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().error()).isEqualTo("Internal Server Error");
        assertThat(response.getBody().message()).isEqualTo("unexpected error");
    }

    @Test
    void handleValidation_returns400WithFieldErrors() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "name", "must not be blank"));
        bindingResult.addError(new FieldError("target", "price", "must be greater than 0"));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ValidationErrorResponse> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Bad Request");
        assertThat(response.getBody().fieldErrors()).containsEntry("name", "must not be blank");
        assertThat(response.getBody().fieldErrors()).containsEntry("price", "must be greater than 0");
        assertThat(response.getBody().timestamp()).isNotNull();
    }
}
