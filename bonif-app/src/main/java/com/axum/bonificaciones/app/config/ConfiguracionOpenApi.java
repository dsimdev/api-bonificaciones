package com.axum.bonificaciones.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConfiguracionOpenApi {

    @Bean
    OpenAPI openApi(@Value("${bonificaciones.version:dev}") String version) {
        return new OpenAPI().info(new Info()
                .title("API Bonificaciones")
                .version(version)
                .description("Gateway de criterios de venta / bonificaciones. Normaliza lo que "
                        + "exponen los ERP (hoy GESCOM) detras de un contrato unico."));
    }
}
