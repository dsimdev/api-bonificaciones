package com.axum.bonificaciones.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.bonificaciones.core.model.Vigencia;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class VigenciaTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 6);

    @Test
    void sinExtremosEstaSiempreVigente() {
        assertTrue(Vigencia.SIEMPRE.vigenteEn(HOY));
    }

    @Test
    void sinFechaHastaSigueVigenteHaciaAdelante() {
        var abierta = new Vigencia(LocalDate.of(2026, 1, 1), null);
        assertTrue(abierta.vigenteEn(HOY));
    }

    @Test
    void losExtremosSonInclusivos() {
        var unDia = new Vigencia(HOY, HOY);
        assertTrue(unDia.vigenteEn(HOY));
        assertFalse(unDia.vigenteEn(HOY.minusDays(1)));
        assertFalse(unDia.vigenteEn(HOY.plusDays(1)));
    }
}
