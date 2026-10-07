package com.axum.bonificaciones.core.model;

import java.math.BigDecimal;

/**
 * Que hace una bonificacion cuando aplica.
 *
 * Sealed con una sola variante a proposito: en GESCOM el unico modificador observado en el
 * catalogo real de dyssa es DescuentoItem, y si aparece otro tipo tiene que romper la compilacion
 * en vez de pasar inadvertido.
 *
 * Hubo tambien PrecioFijo y UnidadesSinCargo, que son operaciones del modelo de bonificaciones de
 * Axum. Se quitaron el 2026-10-07 cuando Axum salio de alcance: no hay por que arrastrar variantes
 * que ninguna fuente nuestra produce.
 */
public sealed interface Operacion {

    /**
     * Descuento porcentual.
     *
     * @param descuento PORCENTAJE, no fraccion: 10 = 10%. GESCOM lo da como fraccion (0.1) dentro
     *                  del configuracionJson del modificador, y lo multiplica por 100 el conector
     *                  -- nunca mas abajo.
     * @param tope      tope del descuento, o null si no tiene
     */
    record Descuento(BigDecimal descuento, BigDecimal tope) implements Operacion {}
}
