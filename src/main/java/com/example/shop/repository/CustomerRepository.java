package com.example.shop.repository;

import com.example.shop.model.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends MongoRepository<Customer, String> {
    Optional<Customer> findByEmail(String email);

    List<Customer> findByNameContainingIgnoreCase(String name);

    Page<Customer> findByAddressCityContainingIgnoreCase(String city, Pageable pageable);

    @Query("{ $or: [ " +
            "{ 'address.street':  { $regex: ?0, $options: 'i' } }, " +
            "{ 'address.city':    { $regex: ?0, $options: 'i' } }, " +
            "{ 'address.country': { $regex: ?0, $options: 'i' } }, " +
            "{ 'address.zipCode': { $regex: ?0, $options: 'i' } } " +
            "] }")
    List<Customer> findByAddressContaining(String keyword);
}
