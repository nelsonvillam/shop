package com.example.shop.mapper;

import com.example.shop.dto.OrderDetailResponseDTO;
import com.example.shop.dto.OrderRequestDTO;
import com.example.shop.dto.OrderResponseDTO;
import com.example.shop.model.Order;
import com.example.shop.model.OrderDetail;
import org.bson.types.ObjectId;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", uses = {CustomerMapper.class, ProductMapper.class})
public interface OrderMapper {

    OrderResponseDTO toResponse(Order order);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "total", ignore = true)
    Order toEntity(OrderRequestDTO dto);

    OrderDetailResponseDTO toDetailResponse(OrderDetail orderDetail);

    default String objectIdToString(ObjectId objectId) {
        return objectId != null ? objectId.toHexString() : null;
    }

    default ObjectId stringToObjectId(String id) {
        return id != null ? new ObjectId(id) : null;
    }
}
