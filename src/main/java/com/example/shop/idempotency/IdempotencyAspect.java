package com.example.shop.idempotency;

import com.example.shop.exception.IdempotencyConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotencyAspect {

    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    @Around("@annotation(com.example.shop.idempotency.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        HttpServletRequest request = ((ServletRequestAttributes)
                RequestContextHolder.currentRequestAttributes()).getRequest();
        String key = request.getHeader("Idempotency-Key");

        if (key == null || key.isBlank()) {
            return pjp.proceed();
        }

        // Cache hit: return the stored response
        IdempotencyRecord existing = mongoTemplate.findById(key, IdempotencyRecord.class);
        if (existing != null) {
            if ("PROCESSING".equals(existing.getStatus())) {
                throw new IdempotencyConflictException(
                        "A request with Idempotency-Key '" + key + "' is already in flight");
            }
            log.info("Idempotency replay for key={}", key);
            Class<?> responseClass = Class.forName(existing.getResponseType());
            return objectMapper.readValue(existing.getResponseBody(), responseClass);
        }

        // Atomically claim the key. If two requests race, the second gets DuplicateKeyException.
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(key);
        record.setStatus("PROCESSING");
        record.setCreatedAt(Instant.now());
        record.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        try {
            mongoTemplate.insert(record);
        } catch (DuplicateKeyException e) {
            throw new IdempotencyConflictException(
                    "A request with Idempotency-Key '" + key + "' is already in flight", e);
        }

        try {
            Object result = pjp.proceed();
            // Mark completed and persist the response for future replays
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(key)),
                    Update.update("status", "COMPLETED")
                          .set("responseBody", objectMapper.writeValueAsString(result))
                          .set("responseType", result.getClass().getName()),
                    IdempotencyRecord.class);
            return result;
        } catch (Throwable t) {
            // Remove the record so the client can retry with the same key
            mongoTemplate.remove(Query.query(Criteria.where("_id").is(key)), IdempotencyRecord.class);
            throw t;
        }
    }
}
