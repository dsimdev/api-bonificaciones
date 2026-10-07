package com.axum.bonificaciones.app.seguridad;

import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Valida el header {@code x-api-key} en /v1/**.
 *
 * Se chequean tres cosas y las tres importan:
 *
 * 1. Que la clave exista y no este revocada.
 * 2. Que sea **del mismo tenant que la URL**. Sin esto, la tienda de una distribuidora podria
 *    pedir los precios y descuentos de otra cambiando la ruta. Es el mismo agujero que el test de
 *    aislamiento cubre del lado de GESCOM, pero del lado de la entrada.
 * 3. Que tenga alcance suficiente: el catalogo (/criterios) es la estructura comercial completa y
 *    **no va con la clave del checkout**, que vive en un navegador y se puede leer del DevTools.
 */
@Component
@ConditionalOnProperty(name = "bonificaciones.distribuidoras-en-base", havingValue = "true",
        matchIfMissing = true)
public class InterceptorDeApiKey implements HandlerInterceptor {

    public static final String HEADER = "x-api-key";

    private final RepositorioDeCredenciales credenciales;
    private final LimitadorDeIntentos limitador;

    InterceptorDeApiKey(RepositorioDeCredenciales credenciales, LimitadorDeIntentos limitador) {
        this.credenciales = credenciales;
        this.limitador = limitador;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        var tenant = tenantDeLaRuta(request);
        if (tenant != null) limitador.verificarNoBloqueado(tenant);

        var clave = request.getHeader(HEADER);
        if (clave == null || clave.isBlank()) {
            limitador.registrarFallo(tenant);
            throw new ErrorDeGateway(CodigoDeError.NO_AUTORIZADO, "Falta el header " + HEADER + ".");
        }

        var credencial = credenciales.buscarPorClave(clave).orElseGet(() -> {
            limitador.registrarFallo(tenant);
            throw new ErrorDeGateway(CodigoDeError.NO_AUTORIZADO, "Clave invalida.");
        });

        if (!credencial.tenant().equals(tenant)) {
            limitador.registrarFallo(tenant);
            // Sin decir de que distribuidora ES la clave: eso le confirmaria a quien prueba que
            // la clave sirve para algo.
            throw new ErrorDeGateway(CodigoDeError.NO_AUTORIZADO,
                    "Esa clave no es de la distribuidora " + tenant + ".");
        }

        if (esCatalogo(request) && !credencial.alcance().puedeLeerElCatalogo()) {
            throw new ErrorDeGateway(CodigoDeError.ALCANCE_INSUFICIENTE,
                    "El catalogo de criterios necesita una clave de alcance ADMIN. La clave del "
                            + "checkout solo puede valorizar.");
        }

        limitador.registrarExito(tenant);
        return true;
    }

    private boolean esCatalogo(HttpServletRequest request) {
        return request.getRequestURI().contains("/criterios");
    }

    @SuppressWarnings("unchecked")
    private String tenantDeLaRuta(HttpServletRequest request) {
        var vars = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return vars == null ? null : vars.get("tenant");
    }
}
