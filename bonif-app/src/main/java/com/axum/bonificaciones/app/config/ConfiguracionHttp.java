package com.axum.bonificaciones.app.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * El RestClient con el que se habla con todas las fuentes.
 *
 * Los timeouts son explicitos y cortos a proposito: esto se llama en un checkout. Una fuente que
 * tarda 30 segundos es una venta perdida igual que una fuente caida, pero ademas deja al
 * consumidor colgado sin poder decidir.
 */
@Configuration
public class ConfiguracionHttp {

    @Bean
    RestClient restClient(RestClient.Builder builder) {
        // HTTP/1.1 fijo. Por defecto el cliente del JDK intenta negociar HTTP/2, y contra
        // servidores que no lo manejan bien la conexion se corta con un "EOF reached while
        // reading" en vez de un error claro -- aparecio en los tests contra el stub, y del otro
        // lado hay un backend .NET con anios encima, asi que no es un riesgo hipotetico.
        var cliente = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        var factory = new JdkClientHttpRequestFactory(cliente);
        factory.setReadTimeout(Duration.ofSeconds(15));

        return builder.requestFactory(factory).build();
    }

    /**
     * El reloj se inyecta en vez de usar OffsetDateTime.now() suelto: `consultadoEn` es parte del
     * contrato, asi que tiene que poder fijarse en un test.
     */
    @Bean
    Clock reloj() {
        return Clock.systemDefaultZone();
    }
}
