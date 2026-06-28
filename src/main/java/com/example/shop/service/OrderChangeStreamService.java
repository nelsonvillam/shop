package com.example.shop.service;

import com.example.shop.dto.OrderChangeEvent;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OrderChangeStreamService {

    private final MongoClient mongoClient;
    private final String databaseName;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<OrderChangeEvent> eventQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "order-change-stream"));
    private final AtomicReference<MongoCursor<ChangeStreamDocument<Document>>> cursorRef =
            new AtomicReference<>();

    public OrderChangeStreamService(
            MongoClient mongoClient,
            @Value("${spring.data.mongodb.database}") String databaseName) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @EventListener(ApplicationReadyEvent.class)
    public void startListening() {
        executor.submit(this::listen);
    }

    @SuppressWarnings("PMD.CloseResource")
    private void listen() {
        try {
            List<Bson> pipeline = List.of(
                    Aggregates.match(Filters.in("operationType", "insert", "update", "replace")));

            cursorRef.set(mongoClient
                    .getDatabase(databaseName)
                    .getCollection("orders")
                    .watch(pipeline)
                    .fullDocument(FullDocument.UPDATE_LOOKUP)
                    .iterator());

            log.info("Order change stream listener ready");

            MongoCursor<ChangeStreamDocument<Document>> c = cursorRef.get();
            while (!Thread.currentThread().isInterrupted()) {
                ChangeStreamDocument<Document> change = c.next();
                OrderChangeEvent event = toEvent(change);
                if (!eventQueue.offer(event)) {
                    log.warn("Event queue full, dropping change stream event: {}", event.orderId());
                }
                broadcast(event);
            }
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.error("Order change stream error: {}", e.getMessage());
            }
        }
    }

    private OrderChangeEvent toEvent(ChangeStreamDocument<Document> change) {
        var rawOpType = change.getOperationType();
        String opType = rawOpType != null ? rawOpType.getValue() : "unknown";
        Document doc = change.getFullDocument();
        if (doc == null) {
            return new OrderChangeEvent(opType, null, null, null, null);
        }
        ObjectId rawId = doc.getObjectId("_id");
        Object custRaw = doc.get("customerId");
        String customerId = custRaw instanceof ObjectId oid ? oid.toHexString() : null;
        return new OrderChangeEvent(
                opType,
                rawId != null ? rawId.toHexString() : null,
                customerId,
                doc.getString("status"),
                doc.getDouble("total"));
    }

    private void broadcast(OrderChangeEvent event) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("order-change").data(event));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    public SseEmitter addEmitter() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(t -> emitters.remove(emitter));
        return emitter;
    }

    public boolean isListening() {
        return cursorRef.get() != null;
    }

    public OrderChangeEvent pollEvent(long timeout, TimeUnit unit) throws InterruptedException {
        return eventQueue.poll(timeout, unit);
    }

    @PreDestroy
    @SuppressWarnings("PMD.CloseResource")
    public void shutdown() {
        executor.shutdownNow();
        MongoCursor<ChangeStreamDocument<Document>> c = cursorRef.getAndSet(null);
        if (c != null) {
            c.close();
        }
    }
}
