package com.axum.bonificaciones.core.model;

import java.math.BigDecimal;

/**
 * Que hace una bonificacion cuando aplica.
 *
 * Son las tres operaciones documentadas por Axum (categoria "Tipo de Operacion"): descuento,
 * precio fijo y unidades sin cargo. GESCOM, de lo que vimos, solo usa descuento (DescuentoItem).
 * Sealed porque el conjunto esta cerrado: una operacion nueva tiene que romper la compilacion,
 * no pasar inadvertida.
 */
public sealed interface Operacion {

    /**
     * Descuento porcentual.
     *
     * @param descuento FRACCION, no porcentaje: 0.1 = 10%. GESCOM ya lo da asi; lo de Axum viene
     *                  como porcentaje (46.57) y lo convierte el conector -- nunca mas abajo.
     * @param tope      tope del descuento (topeDescuento de Axum), o null si no tiene
     */
    record Descuento(BigDecimal descuento, BigDecimal tope) implements Operacion {}

    /**
     * Fija el precio en vez de descontar. Segun la doc de Axum, cuando hay precio fijo el
     * descuento queda en 0 y lo que cambia es el precio.
     */
    record PrecioFijo(BigDecimal precio) implements Operacion {}

    /** Unidades gratis: "lleva 10, paga 9". */
    record UnidadesSinCargo(int cantidad, Integer multiplo) implements Operacion {}
}
