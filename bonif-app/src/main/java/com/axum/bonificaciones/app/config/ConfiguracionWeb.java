package com.axum.bonificaciones.app.config;

import com.axum.bonificaciones.app.seguridad.InterceptorDeSesion;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Donde se enchufa la seguridad de administracion. */
@Configuration
public class ConfiguracionWeb implements WebMvcConfigurer {

    private final InterceptorDeSesion sesion;

    ConfiguracionWeb(org.springframework.beans.factory.ObjectProvider<InterceptorDeSesion> sesion) {
        this.sesion = sesion.getIfAvailable();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (sesion == null) return;
        registry.addInterceptor(sesion)
                .addPathPatterns("/admin/**")
                // El login es la unica puerta sin llave: la clave viaja en el cuerpo.
                .excludePathPatterns("/admin/v1/login");
    }
}
