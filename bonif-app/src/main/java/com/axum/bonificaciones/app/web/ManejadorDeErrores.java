package com.axum.bonificaciones.app.web;

import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras.DistribuidoraDesconocidaException;
import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce todo a {codigo, mensaje, crudo}.
 *
 * El integrador tiene que poder distinguir "el pedido esta mal" de "el ERP esta caido" sin
 * parsear texto: por eso el codigo de dominio va en el cuerpo y no solo el status HTTP.
 */
@RestControllerAdvice
class ManejadorDeErrores {

    @ExceptionHandler(ErrorDeGateway.class)
    ResponseEntity<Dtos.ErrorResponse> deGateway(ErrorDeGateway e) {
        return ResponseEntity.status(e.codigo().http())
                .body(new Dtos.ErrorResponse(e.codigo().name(), e.getMessage(), e.crudo()));
    }

    @ExceptionHandler(DistribuidoraDesconocidaException.class)
    ResponseEntity<Dtos.ErrorResponse> desconocida(DistribuidoraDesconocidaException e) {
        var codigo = CodigoDeError.TENANT_DESCONOCIDO;
        return ResponseEntity.status(codigo.http())
                .body(new Dtos.ErrorResponse(codigo.name(), e.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Dtos.ErrorResponse> invalido(MethodArgumentNotValidException e) {
        var detalle = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("pedido invalido");
        var codigo = CodigoDeError.PEDIDO_INVALIDO;
        return ResponseEntity.status(codigo.http())
                .body(new Dtos.ErrorResponse(codigo.name(), detalle, null));
    }
}
