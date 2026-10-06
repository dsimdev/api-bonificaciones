package com.axum.bonificaciones.core.model;

import java.time.LocalDate;

/**
 * Ventana de validez de un criterio. Los dos extremos son opcionales: en GESCOM un criterio sin
 * {@code validoHasta} esta vigente indefinidamente.
 */
public record Vigencia(LocalDate desde, LocalDate hasta) {

    public static final Vigencia SIEMPRE = new Vigencia(null, null);

    public boolean vigenteEn(LocalDate fecha) {
        if (desde != null && fecha.isBefore(desde)) return false;
        if (hasta != null && fecha.isAfter(hasta)) return false;
        return true;
    }
}
