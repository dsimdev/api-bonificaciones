package com.axum.bonificaciones.core.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Una bonificacion que efectivamente cayo sobre una linea, con por que.
 *
 * @param descuento   PORCENTAJE (10 = 10%), ya normalizado por el conector
 * @param condiciones las condiciones del criterio que la disparo, si se pudo cruzar con el
 *                    catalogo; vacio si no. Es el "por que" -- la fuente devuelve solo id y
 *                    nombre
 */
public record BonificacionAplicada(
        String id,
        String nombre,
        BigDecimal descuento,
        List<Condicion> condiciones) {

    public BonificacionAplicada {
        condiciones = condiciones == null ? List.of() : List.copyOf(condiciones);
    }
}
