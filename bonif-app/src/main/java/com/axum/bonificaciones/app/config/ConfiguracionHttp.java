package com.axum.bonificaciones.app.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Configurables porque no sabemos cuánto tarda GESCOM en el servidor de producción: en una red
     * distinta, con mil distribuidoras y en hora pico, el número que anda en local puede no ser el
     * que anda allá. Poder subirlo sin un release es la diferencia entre un cambio de variable de
     * entorno y un deploy a las 11 de la noche.
     */
    @Bean
    RestClient restClient(RestClient.Builder builder,
                          @Value("${bonificaciones.gescom.timeout-conexion-segundos:5}")
                          long timeoutConexion,
                          @Value("${bonificaciones.gescom.timeout-lectura-segundos:15}")
                          long timeoutLectura) {
        // HTTP/1.1 fijo. Por defecto el cliente del JDK intenta negociar HTTP/2, y contra
        // servidores que no lo manejan bien la conexion se corta con un "EOF reached while
        // reading" en vez de un error claro -- aparecio en los tests contra el stub, y del otro
        // lado hay un backend .NET con anios encima, asi que no es un riesgo hipotetico.
        var cliente = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(timeoutConexion))
                .build();

        var factory = new JdkClientHttpRequestFactory(cliente);
        factory.setReadTimeout(Duration.ofSeconds(timeoutLectura));

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
