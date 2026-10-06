package com.axum.bonificaciones.core.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Una linea del pedido, ya valorizada.
 *
 * @param descuento        PORCENTAJE (10 = 10%)
 * @param bonificaciones   que bonificaciones lo otorgaron
 */
public record LineaValorizada(
        String codigoItem,
        BigDecimal cantidad,
        BigDecimal neto,
        BigDecimal descuento,
        BigDecimal netoConDescuento,
        List<BonificacionAplicada> bonificaciones) {

    public LineaValorizada {
        bonificaciones = bonificaciones == null ? List.of() : List.copyOf(bonificaciones);
    }

    /**
     * Invariante del calculo: el neto menos el descuento tiene que dar exactamente el neto con
     * descuento. Si no cierra, el resultado esta mal y hay que fallar, no redondear en silencio
     * -- es la misma regla que el "neto + tributos == total" de MotorFiscal.
     *
     * La tolerancia es de un centavo porque el ERP redondea a dos decimales y nosotros no
     * reproducimos su redondeo: no estamos verificando nuestra aritmetica sino que la respuesta
     * del ERP sea internamente coherente.
     */
    public boolean cierra() {
        if (neto == null || descuento == null || netoConDescuento == null) return false;
        var esperado = neto.subtract(neto.multiply(descuento).movePointLeft(2));
        return esperado.subtract(netoConDescuento).abs().compareTo(new BigDecimal("0.01")) <= 0;
    }
}
