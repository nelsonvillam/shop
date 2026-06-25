package com.example.shop.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SchemaValidationConfig implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        applyValidator("products", productValidator());
        applyValidator("orders",   orderValidator());
    }

    private void applyValidator(String collection, Document validator) {
        if (!mongoTemplate.collectionExists(collection)) {
            mongoTemplate.createCollection(collection);
        }
        mongoTemplate.executeCommand(
                new Document("collMod", collection)
                        .append("validator",       new Document("$jsonSchema", validator))
                        .append("validationAction", "error")
                        .append("validationLevel",  "strict")
        );
        log.info("Schema validation applied to '{}'", collection);
    }

    // -------------------------------------------------------------------------
    // products schema
    // -------------------------------------------------------------------------
    private Document productValidator() {
        return new Document("bsonType", "object")
                .append("required", List.of("name", "price", "stock"))
                .append("properties", new Document()
                        .append("name", new Document()
                                .append("bsonType", "string")
                                .append("minLength", 1)
                                .append("description", "required, non-empty string"))
                        .append("description", new Document()
                                .append("bsonType", "string"))
                        .append("price", new Document()
                                .append("bsonType", "double")
                                .append("minimum", 0)
                                .append("description", "required, non-negative number"))
                        .append("stock", new Document()
                                .append("bsonType", "int")
                                .append("minimum", 0)
                                .append("description", "required, non-negative integer"))
                );
    }

    // -------------------------------------------------------------------------
    // orders schema
    // -------------------------------------------------------------------------
    private Document orderValidator() {
        return new Document("bsonType", "object")
                .append("required", List.of("customerId", "productIds", "status", "total"))
                .append("properties", new Document()
                        .append("customerId", new Document()
                                .append("bsonType", "objectId")
                                .append("description", "required, must reference a customer"))
                        .append("productIds", new Document()
                                .append("bsonType", "array")
                                .append("minItems", 1)
                                .append("items", new Document("bsonType", "objectId"))
                                .append("description", "required, at least one product reference"))
                        .append("status", new Document()
                                .append("bsonType", "string")
                                .append("enum", List.of("PENDING", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED"))
                                .append("description", "required, must be a valid order status"))
                        .append("total", new Document()
                                .append("bsonType", "double")
                                .append("minimum", 0)
                                .append("description", "required, non-negative total"))
                        .append("createdAt", new Document()
                                .append("bsonType", "date"))
                );
    }
}
