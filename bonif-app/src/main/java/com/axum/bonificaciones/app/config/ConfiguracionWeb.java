package com.axum.bonificaciones.app.config;

import com.axum.bonificaciones.app.seguridad.InterceptorDeApiKey;
import com.axum.bonificaciones.app.seguridad.InterceptorDeSesion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Donde se enchufa la seguridad.
 *
 * Los dos interceptores son opcionales porque solo existen cuando las distribuidoras salen de la
 * base: los tests corren sin base y sin auth, enfocados en el conector y el contrato.
 */
@Configuration
public class ConfiguracionWeb implements WebMvcConfigurer {

    private final InterceptorDeSesion sesion;
    private final InterceptorDeApiKey apiKey;

    ConfiguracionWeb(ObjectProvider<InterceptorDeSesion> sesion,
                     ObjectProvider<InterceptorDeApiKey> apiKey) {
        this.sesion = sesion.getIfAvailable();
        this.apiKey = apiKey.getIfAvailable();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (sesion != null) {
            registry.addInterceptor(sesion)
                    .addPathPatterns("/admin/**")
                    // El login es la unica puerta sin llave: la clave viaja en el cuerpo.
                    .excludePathPatterns("/admin/v1/login");
        }
        if (apiKey != null) {
            // /health queda afuera a proposito: es lo que mira el monitoreo y quien deploya, y no
            // expone nada de ninguna distribuidora.
            registry.addInterceptor(apiKey).addPathPatterns("/v1/**");
        }
    }
}
