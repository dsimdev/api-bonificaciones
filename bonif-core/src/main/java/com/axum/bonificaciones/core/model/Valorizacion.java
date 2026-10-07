package com.axum.bonificaciones.core.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * El resultado de valorizar un pedido.
 *
 * @param supuestos lo que resolvimos nosotros porque el pedido no lo traia. Vacio = no hubo
 *                  ninguno
 *
 * Lleva siempre de donde salio y quien lo calculo: una respuesta que no se puede explicar frente
 * al preventista es un problema de soporte, no un resultado.
 */
public record Valorizacion(
        Fuente fuente,
        String tenant,
        CalculadoPor calculadoPor,
        OffsetDateTime consultadoEn,
        List<Supuesto> supuestos,
        List<LineaValorizada> lineas) {

    public Valorizacion {
        supuestos = supuestos == null ? List.of() : List.copyOf(supuestos);
        lineas = lineas == null ? List.of() : List.copyOf(lineas);
    }

    public Totales totales() {
        return Totales.de(lineas);
    }

    /** Las lineas cuyo descuento no cuadra con el neto informado. Vacio = todo coherente. */
    public List<LineaValorizada> lineasQueNoCierran() {
        return lineas.stream().filter(l -> !l.cierra()).toList();
    }
}
