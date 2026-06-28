package com.example.shop.idempotency;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Getter
@Setter
@Document(collection = "idempotency_records")
public class IdempotencyRecord {

    @Id
    private String idempotencyKey;

    private String status;        // PROCESSING | COMPLETED
    private String responseBody;  // JSON-serialised return value
    private String responseType;  // fully-qualified class name for deserialisation

    private Instant createdAt;

    @Indexed(expireAfterSeconds = 86400)
    private Instant expiresAt;    // TTL — MongoDB auto-deletes after 24 h
}
