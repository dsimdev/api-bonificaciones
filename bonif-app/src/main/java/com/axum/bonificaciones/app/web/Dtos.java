package com.axum.bonificaciones.app.web;

import com.axum.bonificaciones.core.model.CalculadoPor;
import com.axum.bonificaciones.core.model.Fuente;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * El contrato publico. Es propio: no asoma nada de GESCOM ni de Axum.
 *
 * Las validaciones estan aca para que un pedido invalido falle ANTES de gastar una llamada a la
 * fuente -- cada una cuesta un token y tiempo de checkout.
 */
public final class Dtos {

    private Dtos() {}

    public record PedidoRequest(
            @NotBlank String cliente,
            String listaPrecio,
            @NotEmpty @Valid List<ItemRequest> items) {}

    public record ItemRequest(
            @NotBlank String codigo,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal cantidad,
            String unidad,
            BigDecimal unidadFactor) {

        /** GESCOM exige la unidad; "Unidad" es el valor por defecto verificado contra la API. */
        public String unidadODefecto() {
            return unidad == null || unidad.isBlank() ? "Unidad" : unidad;
        }

        public BigDecimal factorODefecto() {
            return unidadFactor == null ? BigDecimal.ONE : unidadFactor;
        }
    }

    /**
     * @param calculadoPor ERP o GATEWAY. Parte del contrato, no debug: "lo dijo el ERP" y "lo
     *                     calculamos nosotros" no valen lo mismo frente a un reclamo
     */
    public record ValorizacionResponse(
            Fuente fuente,
            String tenant,
            CalculadoPor calculadoPor,
            OffsetDateTime consultadoEn,
            List<LineaResponse> lineas) {}

    /** @param descuento PORCENTAJE: 10 = 10%. */
    public record LineaResponse(
            String codigo,
            BigDecimal cantidad,
            BigDecimal neto,
            BigDecimal descuento,
            BigDecimal netoConDescuento,
            List<BonificacionResponse> bonificaciones) {}

    /**
     * @param descuento PORCENTAJE: 10 = 10%
     * @param condiciones por que aplico, cuando se pudo cruzar con el catalogo. Vacio no significa
     *                    "sin condiciones": significa que no se pudo enriquecer
     */
    public record BonificacionResponse(
            String id,
            String nombre,
            BigDecimal descuento,
            List<CondicionResponse> condiciones) {}

    public record CondicionResponse(
            String tipo,
            List<String> valores,
            boolean invertida,
            Integer cantidadMinima) {}

    public record ErrorResponse(String codigo, String mensaje, String crudo) {}
}
