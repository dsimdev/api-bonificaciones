package com.axum.bonificaciones.core.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * El resultado de valorizar un pedido.
 *
 * Lleva siempre de donde salio y quien lo calculo: una respuesta que no se puede explicar frente
 * al preventista es un problema de soporte, no un resultado.
 */
public record Valorizacion(
        Fuente fuente,
        String tenant,
        CalculadoPor calculadoPor,
        OffsetDateTime consultadoEn,
        List<LineaValorizada> lineas) {

    public Valorizacion {
        lineas = lineas == null ? List.of() : List.copyOf(lineas);
    }

    /** Las lineas cuyo descuento no cuadra con el neto informado. Vacio = todo coherente. */
    public List<LineaValorizada> lineasQueNoCierran() {
        return lineas.stream().filter(l -> !l.cierra()).toList();
    }
}
