package com.axum.bonificaciones.core.model;

import java.util.List;

/**
 * Lo que hace el criterio cuando aplica, y sobre que items cae.
 *
 * @param operacion            descuento, precio fijo o unidades sin cargo
 * @param condicionesDeDatos   a que items cae (codigos de condicion); vacio = a todos los que
 *                             califican. Es de GESCOM; en Axum cada fila es autocontenida
 * @param permiteSuperposicion si apila con otros criterios sobre el mismo item
 */
public record Modificador(
        Operacion operacion,
        List<Integer> condicionesDeDatos,
        boolean permiteSuperposicion) {

    public Modificador {
        condicionesDeDatos = condicionesDeDatos == null ? List.of() : List.copyOf(condicionesDeDatos);
    }
}
