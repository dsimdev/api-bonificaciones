package com.axum.bonificaciones.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axum.bonificaciones.core.model.CalculadoPor;
import com.axum.bonificaciones.core.model.ItemAValorizar;
import com.axum.bonificaciones.core.model.PedidoAValorizar;
import com.axum.bonificaciones.core.puerto.Valorizador;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Los dos casos verificados en vivo contra GESCOM, pasando por nuestro codigo.
 *
 * Es el criterio de salida de la Fase 1: lo que prueba que lo reverse-engineereado sigue siendo
 * cierto. Un test con WireMock prueba que nuestro conector hace lo que creemos; este prueba que lo
 * que creemos es lo que GESCOM hace.
 *
 * Etiquetado "erp": excluido del build salvo -PincludeErpTests, porque necesita credenciales
 * reales y red. Cargar el .env antes de correrlo (ver docs/proyecto/entorno-local.md).
 *
 * Los numeros salen de C:\Dev\docs\gescom\eval-pedido.md, verificados el 2026-10-06.
 */
@Tag("erp")
@SpringBootTest
class ValorizacionContraGescomRealIT {

    @Autowired
    Valorizador valorizador;

    @Autowired
    com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras configuracion;

    /** dyssa, criterio 558: 10% sobre el item 5000014792 en la lista 2. */
    @Test
    void reproduceElCasoVerificadoDeDyssa() {
        valorizaYVerifica("dyssa", "8380", "5000014792", "2",
                new BigDecimal("58424.22"), new BigDecimal("10"), new BigDecimal("52581.80"));
    }

    /** senderolaser, criterio 159: 12% sobre el item 610030 en la lista 1. */
    @Test
    void reproduceElCasoVerificadoDeSenderolaser() {
        valorizaYVerifica("senderolaser", "301", "610030", "1",
                new BigDecimal("6201.06"), new BigDecimal("12"), new BigDecimal("5456.93"));
    }

    private void valorizaYVerifica(String tenant, String cliente, String item, String lista,
                                   BigDecimal neto, BigDecimal descuento, BigDecimal conDescuento) {
        var distribuidora = configuracion.distribuidoras().get(tenant);
        assumeTrue(distribuidora != null && distribuidora.gescom() != null
                        && distribuidora.gescom().usuario() != null
                        && !distribuidora.gescom().usuario().isBlank(),
                "Sin credenciales de " + tenant + " en el entorno: se saltea");

        var pedido = new PedidoAValorizar(cliente, lista, List.of(
                new ItemAValorizar(item, new BigDecimal("6"), "Unidad", BigDecimal.ONE)));

        var resultado = valorizador.valorizar(tenant, pedido);

        assertEquals(CalculadoPor.ERP, resultado.calculadoPor(),
                "el numero lo tiene que dar el ERP, no nosotros");
        assertEquals(1, resultado.lineas().size());

        var linea = resultado.lineas().get(0);
        assertEquals(0, neto.compareTo(linea.neto()), "neto");
        assertEquals(0, descuento.compareTo(linea.descuento()),
                "descuento en PORCENTAJE (si da " + descuento.movePointLeft(2)
                        + " es que salio sin convertir de fraccion)");
        assertEquals(0, conDescuento.compareTo(linea.netoConDescuento()), "neto con descuento");
        assertTrue(linea.cierra(), "la linea tiene que cerrar");
    }
}
