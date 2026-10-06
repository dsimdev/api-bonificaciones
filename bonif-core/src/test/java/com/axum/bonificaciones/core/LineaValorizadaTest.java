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
                new BigDecimal(descuento), new BigDecimal(netoConDesc), List.of());
    }

    /** El caso real verificado en dyssa: 58424.22 con 10% da 52581.80. */
    @Test
    void elCasoRealDeDyssaCierra() {
        assertTrue(linea("58424.22", "10", "52581.80").cierra());
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
