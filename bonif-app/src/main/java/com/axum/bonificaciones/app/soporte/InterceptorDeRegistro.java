package com.axum.bonificaciones.app.soporte;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Mide y registra cada llamada de la API pública.
 *
 * Se mide acá, en el borde, y no adentro del conector: lo que se quiere saber cuando una tienda
 * reclama es <b>qué le respondimos nosotros y cuánto tardamos en total</b>, que es lo que ella
 * vio. Medir solo la llamada al ERP dejaría afuera el token, el catálogo y nuestro propio trabajo.
 *
 * El código de error de dominio lo deja {@code ManejadorDeErrores} en un atributo del request: es
 * el único que lo conoce, porque para cuando la respuesta llega acá ya es un status HTTP y un
 * cuerpo. Sin eso el registro diría "falló con 502" en vez de "falló con FUENTE_NO_DISPONIBLE",
 * que es la diferencia entre saber y no saber qué pasó.
 */
@Component
public class InterceptorDeRegistro implements HandlerInterceptor {

    /** Lo pone ManejadorDeErrores y lo lee este interceptor. */
    public static final String ATRIBUTO_CODIGO = "bonificaciones.codigoDeError";

    private static final String ATRIBUTO_INICIO = "bonificaciones.inicio";

    private final RegistroDeLlamadas registro;

    InterceptorDeRegistro(RegistroDeLlamadas registro) {
        this.registro = registro;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        request.setAttribute(ATRIBUTO_INICIO, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        var inicio = request.getAttribute(ATRIBUTO_INICIO);
        if (!(inicio instanceof Long nanos)) return;
        var ms = (System.nanoTime() - nanos) / 1_000_000;

        var codigo = (String) request.getAttribute(ATRIBUTO_CODIGO);
        if (codigo == null && response.getStatus() >= 400) {
            // Un 4xx/5xx que no vino de un ErrorDeGateway (un 404 de ruta, un 405). No tiene
            // codigo de dominio, pero contarlo como exito seria mentir.
            codigo = "HTTP_" + response.getStatus();
        }
        registro.registrar(tenantDe(request), operacionDe(request), codigo, ms);
    }

    /**
     * El tenant sale de la variable de ruta, no de parsear la URL: es la misma que resolvió Spring
     * para el controller, así que no se puede despegar de la que se usó de verdad.
     */
    private String tenantDe(HttpServletRequest request) {
        var vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> mapa) {
            var tenant = mapa.get("tenant");
            if (tenant != null) return tenant.toString();
        }
        return null;
    }

    private String operacionDe(HttpServletRequest request) {
        // El patrón de la ruta y no la URI: con la URI, cada tenant sería una operación distinta
        // y no se podría agrupar nada.
        var patron = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return request.getMethod() + " " + (patron == null ? request.getRequestURI() : patron);
    }
}
