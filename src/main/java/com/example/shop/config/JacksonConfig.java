package com.example.shop.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.bson.types.ObjectId;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer objectIdSerializers() {
        return builder -> builder
                .serializerByType(ObjectId.class, new JsonSerializer<ObjectId>() {
                    @Override
                    public void serialize(ObjectId value, JsonGenerator gen, SerializerProvider serializers)
                            throws IOException {
                        gen.writeString(value.toHexString());
                    }
                })
                .deserializerByType(ObjectId.class, new JsonDeserializer<ObjectId>() {
                    @Override
                    public ObjectId deserialize(JsonParser p, DeserializationContext ctxt)
                            throws IOException {
                        return new ObjectId(p.getText());
                    }
                });
    }
}
