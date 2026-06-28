package com.example.shop.unit.config;

import com.example.shop.config.OpenApiConfig;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private OpenApiConfig config() {
        return new OpenApiConfig();
    }

    @Test
    void openAPI_withoutServerUrl_hasNoServers() {
        OpenApiConfig cfg = config();
        ReflectionTestUtils.setField(cfg, "serverUrl", "");

        OpenAPI api = cfg.openAPI();

        assertThat(api.getServers()).isNullOrEmpty();
    }

    @Test
    void openAPI_withServerUrl_setsServerEntry() {
        OpenApiConfig cfg = config();
        ReflectionTestUtils.setField(cfg, "serverUrl", "http://localhost:9090");

        OpenAPI api = cfg.openAPI();

        assertThat(api.getServers()).hasSize(1);
        assertThat(api.getServers().get(0).getUrl()).isEqualTo("http://localhost:9090");
    }

    @Test
    void openAPI_hasJwtBearerSecurityScheme() {
        OpenApiConfig cfg = config();
        ReflectionTestUtils.setField(cfg, "serverUrl", "");

        OpenAPI api = cfg.openAPI();

        assertThat(api.getComponents().getSecuritySchemes()).containsKey("bearerAuth");
        assertThat(api.getSecurity()).isNotEmpty();
    }

    @Test
    void openAPI_hasExpectedInfo() {
        OpenApiConfig cfg = config();
        ReflectionTestUtils.setField(cfg, "serverUrl", "");

        OpenAPI api = cfg.openAPI();

        assertThat(api.getInfo().getTitle()).isEqualTo("Shop API");
        assertThat(api.getInfo().getVersion()).isEqualTo("v1.0");
    }
}
