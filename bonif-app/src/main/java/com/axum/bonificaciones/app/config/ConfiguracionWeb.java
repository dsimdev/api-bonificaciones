package com.axum.bonificaciones.app.config;

import com.axum.bonificaciones.app.seguridad.InterceptorDeApiKey;
import com.axum.bonificaciones.app.seguridad.InterceptorDeSesion;
import com.axum.bonificaciones.app.soporte.InterceptorDeRegistro;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
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
    private final InterceptorDeRegistro registro;

    ConfiguracionWeb(ObjectProvider<InterceptorDeSesion> sesion,
                     ObjectProvider<InterceptorDeApiKey> apiKey,
                     ObjectProvider<InterceptorDeRegistro> registro) {
        this.sesion = sesion.getIfAvailable();
        this.apiKey = apiKey.getIfAvailable();
        this.registro = registro.getIfAvailable();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // PRIMERO, y esto importa: Spring corre los preHandle en el orden en que se registran, y
        // cuando uno rechaza el pedido solo llama al afterCompletion de los que ya habian pasado.
        // Registrado al final, este interceptor no veia NINGUN pedido rechazado por la
        // autenticacion -- justo el que hace que nadie entienda por que la tienda "no funciona".
        // Verificado en vivo: con el orden invertido, un pedido sin api-key no quedaba registrado.
        // Primero tambien significa que mide el tiempo completo, autenticacion incluida.
        if (registro != null) {
            registry.addInterceptor(registro).addPathPatterns("/v1/**");
        }

        if (sesion != null) {
            // /admin/v1/** y NO /admin/**: bajo /admin viven dos cosas distintas -- la API de
            // administracion y los archivos del panel (/admin/index.html, /admin/_next/...).
            // Con /admin/** el panel no podria ni cargar: pediria su propio HTML sin token y se
            // comeria un 401 antes de poder mostrar la pantalla de login.
            registry.addInterceptor(sesion)
                    .addPathPatterns("/admin/v1/**")
                    // El login es la unica puerta sin llave: la clave viaja en el cuerpo.
                    .excludePathPatterns("/admin/v1/login");
        }
        if (apiKey != null) {
            // /health queda afuera a proposito: es lo que mira el monitoreo y quien deploya, y no
            // expone nada de ninguna distribuidora.
            registry.addInterceptor(apiKey).addPathPatterns("/v1/**");
        }
    }

    /**
     * El panel es un export estatico de Next: {@code /admin} tiene que resolver a su index. Spring
     * hace eso solo para la raiz del sitio, no para un subdirectorio.
     *
     * <p>FORWARD, nunca {@code redirect:} -- y en api-impuestos no siempre fue asi. Un redirect le
     * manda al navegador un {@code Location: /admin/} armado con la ruta que ve ESTE proceso, no
     * la que ve el navegador: detras de un IIS que cuelgue esto como aplicacion anidada, el
     * navegador terminaria en {@code /admin} de la RAIZ del dominio, afuera de la ruta anidada.
     * Un forward no emite Location, asi que no hay nada que un proxy tenga que reescribir. Es el
     * mismo bug que llego a produccion tres veces en api-impuestos.
     *
     * <p>El HTML del panel queda publico a proposito: no contiene ninguna credencial. La
     * contrasenia la escribe quien lo usa y el token vive en su sessionStorage, nunca en el bundle.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/admin").setViewName("forward:/admin/index.html");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }
}
