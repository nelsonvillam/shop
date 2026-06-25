package com.example.shop.mapper;

import com.example.shop.dto.AddressDTO;
import com.example.shop.dto.CustomerRequestDTO;
import com.example.shop.dto.CustomerResponseDTO;
import com.example.shop.model.Address;
import com.example.shop.model.Customer;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-22T18:16:09-0400",
    comments = "version: 1.5.5.Final, compiler: javac, environment: Java 21.0.11 (Homebrew)"
)
@Component
public class CustomerMapperImpl implements CustomerMapper {

    @Override
    public CustomerResponseDTO toResponse(Customer customer) {
        if ( customer == null ) {
            return null;
        }

        CustomerResponseDTO customerResponseDTO = new CustomerResponseDTO();

        customerResponseDTO.setId( customer.getId() );
        customerResponseDTO.setName( customer.getName() );
        customerResponseDTO.setEmail( customer.getEmail() );
        customerResponseDTO.setAddress( addressToAddressDTO( customer.getAddress() ) );

        return customerResponseDTO;
    }

    @Override
    public Customer toEntity(CustomerRequestDTO dto) {
        if ( dto == null ) {
            return null;
        }

        Customer customer = new Customer();

        customer.setName( dto.getName() );
        customer.setEmail( dto.getEmail() );
        customer.setAddress( addressDTOToAddress( dto.getAddress() ) );

        return customer;
    }

    @Override
    public void updateEntity(CustomerRequestDTO dto, Customer customer) {
        if ( dto == null ) {
            return;
        }

        customer.setName( dto.getName() );
        customer.setEmail( dto.getEmail() );
        if ( dto.getAddress() != null ) {
            if ( customer.getAddress() == null ) {
                customer.setAddress( new Address() );
            }
            addressDTOToAddress1( dto.getAddress(), customer.getAddress() );
        }
        else {
            customer.setAddress( null );
        }
    }

    protected AddressDTO addressToAddressDTO(Address address) {
        if ( address == null ) {
            return null;
        }

        AddressDTO addressDTO = new AddressDTO();

        addressDTO.setStreet( address.getStreet() );
        addressDTO.setCity( address.getCity() );
        addressDTO.setCountry( address.getCountry() );
        addressDTO.setZipCode( address.getZipCode() );

        return addressDTO;
    }

    protected Address addressDTOToAddress(AddressDTO addressDTO) {
        if ( addressDTO == null ) {
            return null;
        }

        Address address = new Address();

        address.setStreet( addressDTO.getStreet() );
        address.setCity( addressDTO.getCity() );
        address.setCountry( addressDTO.getCountry() );
        address.setZipCode( addressDTO.getZipCode() );

        return address;
    }

    protected void addressDTOToAddress1(AddressDTO addressDTO, Address mappingTarget) {
        if ( addressDTO == null ) {
            return;
        }

        mappingTarget.setStreet( addressDTO.getStreet() );
        mappingTarget.setCity( addressDTO.getCity() );
        mappingTarget.setCountry( addressDTO.getCountry() );
        mappingTarget.setZipCode( addressDTO.getZipCode() );
    }
}
