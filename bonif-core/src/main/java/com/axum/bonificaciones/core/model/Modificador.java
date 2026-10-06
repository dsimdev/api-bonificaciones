package com.axum.bonificaciones.core.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Que hace el criterio cuando aplica.
 *
 * @param descuento           fraccion, no porcentaje: 0.1 = 10%. Asi lo devuelve GESCOM y asi se
 *                            expone, para que el numero del gateway sea comparable uno a uno con
 *                            el de la respuesta de eval-pedido sin conversiones en el medio.
 * @param condicionesDeDatos  a que items cae el descuento (codigos de condicion); vacio = a todos
 *                            los que califican
 * @param permiteSuperposicion si apila con otros criterios sobre el mismo item
 */
public record Modificador(
        BigDecimal descuento,
        List<Integer> condicionesDeDatos,
        boolean permiteSuperposicion) {

    public Modificador {
        condicionesDeDatos = condicionesDeDatos == null ? List.of() : List.copyOf(condicionesDeDatos);
    }
}
