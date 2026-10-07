package com.axum.bonificaciones.core.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * El total del pedido. Es la suma de las lineas, sin redondear.
 *
 * Se devuelve calculado para que la tienda no tenga que sumar y arriesgarse a divergir por
 * redondeo. El redondeo para mostrar es decision de ella.
 *
 * @param descuento el ahorro en PESOS, no un porcentaje: un porcentaje de todo el pedido no
 *                  significa nada cuando cada linea tiene el suyo
 */
public record Totales(BigDecimal neto, BigDecimal descuento, BigDecimal netoConDescuento) {

    public static Totales de(List<LineaValorizada> lineas) {
        var neto = BigDecimal.ZERO;
        var conDescuento = BigDecimal.ZERO;
        for (var l : lineas) {
            if (l.neto() != null) neto = neto.add(l.neto());
            if (l.netoConDescuento() != null) conDescuento = conDescuento.add(l.netoConDescuento());
        }
        return new Totales(neto, neto.subtract(conDescuento), conDescuento);
    }
}
