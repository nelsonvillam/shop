package com.example.shop.mapper;

import com.example.shop.dto.OrderDetailResponseDTO;
import com.example.shop.dto.OrderRequestDTO;
import com.example.shop.dto.OrderResponseDTO;
import com.example.shop.dto.ProductResponseDTO;
import com.example.shop.model.Order;
import com.example.shop.model.OrderDetail;
import com.example.shop.model.Product;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-22T18:16:09-0400",
    comments = "version: 1.5.5.Final, compiler: javac, environment: Java 21.0.11 (Homebrew)"
)
@Component
public class OrderMapperImpl implements OrderMapper {

    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private ProductMapper productMapper;

    @Override
    public OrderResponseDTO toResponse(Order order) {
        if ( order == null ) {
            return null;
        }

        OrderResponseDTO orderResponseDTO = new OrderResponseDTO();

        orderResponseDTO.setId( order.getId() );
        orderResponseDTO.setCustomerId( objectIdToString( order.getCustomerId() ) );
        orderResponseDTO.setProductIds( objectIdListToStringList( order.getProductIds() ) );
        orderResponseDTO.setStatus( order.getStatus() );
        orderResponseDTO.setCreatedAt( order.getCreatedAt() );
        orderResponseDTO.setTotal( order.getTotal() );

        return orderResponseDTO;
    }

    @Override
    public Order toEntity(OrderRequestDTO dto) {
        if ( dto == null ) {
            return null;
        }

        Order order = new Order();

        order.setCustomerId( stringToObjectId( dto.getCustomerId() ) );
        order.setProductIds( stringListToObjectIdList( dto.getProductIds() ) );

        return order;
    }

    @Override
    public OrderDetailResponseDTO toDetailResponse(OrderDetail orderDetail) {
        if ( orderDetail == null ) {
            return null;
        }

        OrderDetailResponseDTO orderDetailResponseDTO = new OrderDetailResponseDTO();

        orderDetailResponseDTO.setId( orderDetail.getId() );
        orderDetailResponseDTO.setStatus( orderDetail.getStatus() );
        orderDetailResponseDTO.setCreatedAt( orderDetail.getCreatedAt() );
        orderDetailResponseDTO.setTotal( orderDetail.getTotal() );
        orderDetailResponseDTO.setCustomer( customerMapper.toResponse( orderDetail.getCustomer() ) );
        orderDetailResponseDTO.setProducts( productListToProductResponseDTOList( orderDetail.getProducts() ) );

        return orderDetailResponseDTO;
    }

    protected List<String> objectIdListToStringList(List<ObjectId> list) {
        if ( list == null ) {
            return null;
        }

        List<String> list1 = new ArrayList<String>( list.size() );
        for ( ObjectId objectId : list ) {
            list1.add( objectIdToString( objectId ) );
        }

        return list1;
    }

    protected List<ObjectId> stringListToObjectIdList(List<String> list) {
        if ( list == null ) {
            return null;
        }

        List<ObjectId> list1 = new ArrayList<ObjectId>( list.size() );
        for ( String string : list ) {
            list1.add( stringToObjectId( string ) );
        }

        return list1;
    }

    protected List<ProductResponseDTO> productListToProductResponseDTOList(List<Product> list) {
        if ( list == null ) {
            return null;
        }

        List<ProductResponseDTO> list1 = new ArrayList<ProductResponseDTO>( list.size() );
        for ( Product product : list ) {
            list1.add( productMapper.toResponse( product ) );
        }

        return list1;
    }
}
