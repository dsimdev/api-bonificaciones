package com.axum.bonificaciones.core.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Una linea del pedido, ya valorizada.
 *
 * @param descuento      PORCENTAJE (10 = 10%)
 * @param creadaPorPromo true cuando la linea NO la pidio el cliente: la agrego una bonificacion.
 *                       En GESCOM pasa con los modificadores AgregaGratis -- los "5+1 sin cargo"
 *                       y los combos. Es parte del contrato: el consumidor tiene que poder
 *                       distinguir lo que el cliente pidio de lo que le regalaron
 * @param bonificaciones que bonificaciones la afectaron
 */
public record LineaValorizada(
        String codigoItem,
        BigDecimal cantidad,
        BigDecimal neto,
        BigDecimal descuento,
        BigDecimal netoConDescuento,
        boolean creadaPorPromo,
        List<BonificacionAplicada> bonificaciones) {

    public LineaValorizada {
        bonificaciones = bonificaciones == null ? List.of() : List.copyOf(bonificaciones);
    }

    /**
     * Invariante del calculo: el neto menos el descuento tiene que dar exactamente el neto con
     * descuento. Si no cierra, el resultado esta mal y hay que fallar, no redondear en silencio
     * -- es la misma regla que el "neto + tributos == total" de MotorFiscal.
     *
     * Dos excepciones deliberadas:
     *
     * 1. Las lineas creadas por promo NO se verifican. Un item sin cargo puede venir con neto
     *    normal y precio final cero sin que el descuento lo explique, y rechazar la valorizacion
     *    entera por eso seria voltear un checkout correcto. El catalogo real de dyssa tiene ocho
     *    criterios AgregaGratis, asi que esto pasa seguido.
     * 2. La tolerancia es de un centavo. El ERP devuelve seis decimales (58424.220000 ->
     *    52581.7980000) y no reproducimos su redondeo: lo que se verifica es que su respuesta sea
     *    internamente coherente, no nuestra aritmetica.
     */
    public boolean cierra() {
        if (creadaPorPromo) return true;
        if (neto == null || descuento == null || netoConDescuento == null) return false;
        var esperado = neto.subtract(neto.multiply(descuento).movePointLeft(2));
        return esperado.subtract(netoConDescuento).abs().compareTo(new BigDecimal("0.01")) <= 0;
    }
}
