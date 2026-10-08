package com.axum.bonificaciones.app.web;

import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras.DistribuidoraDesconocidaException;
import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.axum.bonificaciones.app.soporte.InterceptorDeRegistro;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduce todo a {codigo, mensaje, crudo}.
 *
 * El integrador tiene que poder distinguir "el pedido esta mal" de "el ERP esta caido" sin
 * parsear texto: por eso el codigo de dominio va en el cuerpo y no solo el status HTTP.
 */
@RestControllerAdvice
class ManejadorDeErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorDeErrores.class);

    @ExceptionHandler(ErrorDeGateway.class)
    ResponseEntity<Dtos.ErrorResponse> deGateway(ErrorDeGateway e, HttpServletRequest request) {
        return responder(request, e.codigo(), e.getMessage(), e.crudo());
    }

    @ExceptionHandler(DistribuidoraDesconocidaException.class)
    ResponseEntity<Dtos.ErrorResponse> desconocida(DistribuidoraDesconocidaException e,
                                                   HttpServletRequest request) {
        return responder(request, CodigoDeError.TENANT_DESCONOCIDO, e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Dtos.ErrorResponse> invalido(MethodArgumentNotValidException e,
                                                HttpServletRequest request) {
        var detalle = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("pedido invalido");
        return responder(request, CodigoDeError.PEDIDO_INVALIDO, detalle, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Dtos.ErrorResponse> noEncontrado(NoResourceFoundException e,
                                                     HttpServletRequest request) {
        return ResponseEntity.status(404)
                .body(new Dtos.ErrorResponse("NO_ENCONTRADO", "Ruta no encontrada.", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Dtos.ErrorResponse> inesperado(Exception e, HttpServletRequest request) {
        log.error("Error inesperado en {}", request.getRequestURI(), e);
        return responder(request, CodigoDeError.ERROR_INTERNO,
                "Error interno del gateway. Revisa los logs del servidor.", null);
    }

    /**
     * Deja el codigo de dominio en el request antes de responder.
     *
     * Es el unico lugar que lo conoce: para cuando la respuesta llega al interceptor que registra
     * la llamada ya es un status HTTP y un cuerpo. Sin esto el registro diria "fallo con 502" en
     * vez de "fallo con FUENTE_NO_DISPONIBLE", que es la diferencia entre saber y no saber que
     * paso cuando una tienda reclama.
     */
    private ResponseEntity<Dtos.ErrorResponse> responder(HttpServletRequest request,
                                                         CodigoDeError codigo, String mensaje,
                                                         String crudo) {
        request.setAttribute(InterceptorDeRegistro.ATRIBUTO_CODIGO, codigo.name());
        return ResponseEntity.status(codigo.http())
                .body(new Dtos.ErrorResponse(codigo.name(), mensaje, crudo));
    }
}
