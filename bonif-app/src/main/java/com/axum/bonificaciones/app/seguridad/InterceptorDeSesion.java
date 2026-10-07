package com.axum.bonificaciones.app.seguridad;

import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Protege /admin/** con la sesion del panel: {@code Authorization: Bearer <token>}.
 *
 * El token sale de /admin/v1/login, que es el unico endpoint de administracion sin proteger.
 */
@Component
@ConditionalOnProperty(name = "bonificaciones.distribuidoras-en-base", havingValue = "true",
        matchIfMissing = true)
public class InterceptorDeSesion implements HandlerInterceptor {

    private final ServicioDeSesiones sesiones;

    InterceptorDeSesion(ServicioDeSesiones sesiones) {
        this.sesiones = sesiones;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        var token = tokenDe(request);
        var usuario = sesiones.usuarioDe(token)
                .orElseThrow(() -> new ErrorDeGateway(CodigoDeError.NO_AUTORIZADO,
                        "Sesion invalida o vencida: volve a entrar."));
        // Queda disponible para auditar quien hizo cada cosa.
        ContextoDeLlamada.establecer(usuario);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // El hilo se reusa entre pedidos: sin esto, un pedido sin sesion heredaria el usuario del
        // anterior y la auditoria mentiria.
        ContextoDeLlamada.limpiar();
    }

    private String tokenDe(HttpServletRequest request) {
        var header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        return header.substring("Bearer ".length()).trim();
    }
}
