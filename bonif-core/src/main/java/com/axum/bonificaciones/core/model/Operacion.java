package com.axum.bonificaciones.core.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Que hace una bonificacion cuando aplica.
 *
 * Las tres variantes salieron de recorrer el catalogo REAL de dyssa (70 criterios, 2026-10-07).
 * Antes de eso solo se conocia DescuentoItem, y los otros dos aparecieron justamente porque un
 * tipo no reconocido queda marcado en vez de pasar como 0% -- que es el motivo de esa regla.
 *
 * Sealed a proposito: un cuarto tipo tiene que romper la compilacion.
 */
public sealed interface Operacion {

    /**
     * Descuento porcentual plano. {@code DescuentoItem}, el mas comun.
     *
     * @param descuento PORCENTAJE, no fraccion: 10 = 10%. GESCOM lo da como fraccion (0.1) dentro
     *                  del configuracionJson del modificador, y lo multiplica por 100 el conector
     * @param tope      tope del descuento, o null si no tiene
     */
    record Descuento(BigDecimal descuento, BigDecimal tope) implements Operacion {}

    /**
     * Descuento escalonado por cantidad. {@code TablaDescuentoItem}.
     *
     * Ej. del criterio 610 de dyssa: {@code "tabla":[[3,0.05],[45,0.12]]} = desde 3 unidades 5%,
     * desde 45 unidades 12%.
     *
     * @param tramos ordenados por cantidad ascendente
     * @param porCantidad el {@code descuentoPorCantidad} del ERP. Sin verificar que hace cuando
     *                    esta en false: en dyssa esta en true en los dos criterios que lo usan
     */
    record EscalaDeDescuento(List<Tramo> tramos, boolean porCantidad) implements Operacion {

        public EscalaDeDescuento {
            tramos = tramos == null ? List.of() : List.copyOf(tramos);
        }

        /** @param descuento PORCENTAJE (10 = 10%), ya convertido */
        public record Tramo(BigDecimal desdeCantidad, BigDecimal descuento) {}
    }

    /**
     * Agrega unidades sin cargo de un item puntual. {@code AgregaGratis} -- los "5+1 sin cargo" y
     * los combos del catalogo de dyssa.
     *
     * OJO: esto hace que eval-pedido devuelva LINEAS DE MAS, marcadas con creadoPorPromo.
     */
    record ItemSinCargo(String codigoItem, BigDecimal cantidad) implements Operacion {}
}
