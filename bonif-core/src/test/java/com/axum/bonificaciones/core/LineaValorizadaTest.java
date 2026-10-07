package com.axum.bonificaciones.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.bonificaciones.core.model.LineaValorizada;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineaValorizadaTest {

    private static LineaValorizada linea(String neto, String descuento, String netoConDesc) {
        return new LineaValorizada("5000014792", new BigDecimal("6"), new BigDecimal(neto),
                new BigDecimal(descuento), new BigDecimal(netoConDesc), false, List.of());
    }

    /**
     * El caso real verificado en dyssa, con los decimales que devuelve el ERP de verdad:
     * 58424.220000 con 10% da 52581.7980000. La doc de referencia lo muestra redondeado a
     * 52581.80 -- de ahi salio una expectativa equivocada en el test contra GESCOM real.
     */
    @Test
    void elCasoRealDeDyssaCierra() {
        assertTrue(linea("58424.220000", "10", "52581.7980000").cierra());
        assertTrue(linea("58424.22", "10", "52581.80").cierra(), "y tambien redondeado");
    }

    /**
     * Una linea que agrego una promo (AgregaGratis: los "5+1 sin cargo") no se verifica: puede
     * venir con neto normal y precio final cero sin que el descuento lo explique, y rechazar la
     * valorizacion entera por eso seria voltear un checkout correcto.
     */
    @Test
    void unaLineaCreadaPorPromoNoSeVerifica() {
        var regalada = new LineaValorizada("1331001095", new BigDecimal("1"),
                new BigDecimal("1000.00"), BigDecimal.ZERO, BigDecimal.ZERO, true, List.of());
        assertTrue(regalada.cierra());
    }

    /** El caso real verificado en senderolaser: 6201.06 con 12% da 5456.93. */
    @Test
    void elCasoRealDeSenderolaserCierra() {
        assertTrue(linea("6201.06", "12", "5456.93").cierra());
    }

    @Test
    void sinDescuentoElNetoNoCambia() {
        assertTrue(linea("1000.00", "0", "1000.00").cierra());
    }

    /**
     * El bug que este invariante existe para atrapar: el descuento de GESCOM llega como fraccion
     * (0.1) y sale sin multiplicar por 100. El numero "parece" razonable y no cierra.
     */
    @Test
    void unDescuentoSinConvertirDeFraccionAPorcentajeNoCierra() {
        assertFalse(linea("58424.22", "0.1", "52581.80").cierra());
    }

    @Test
    void noCierraSiElNetoConDescuentoNoCorresponde() {
        assertFalse(linea("58424.22", "10", "58424.22").cierra());
    }
}
