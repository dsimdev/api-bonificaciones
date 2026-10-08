package com.axum.bonificaciones.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axum.bonificaciones.core.model.CalculadoPor;
import com.axum.bonificaciones.core.model.ItemAValorizar;
import com.axum.bonificaciones.core.model.LineaValorizada;
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

    /**
     * dyssa, criterio 558: 10% sobre el item 5000014792 en la lista 2.
     *
     * Los decimales son los que devuelve el ERP de verdad: 58424.220000 -> 52581.7980000. La doc
     * de referencia los muestra redondeados a 52581.80 -- esa doc es para leer, no para fijar
     * expectativas de un test.
     */
    @Test
    void reproduceElCasoVerificadoDeDyssa() {
        valorizaYVerifica("dyssa", "8380", "5000014792", "2",
                new BigDecimal("58424.22"), new BigDecimal("10"), new BigDecimal("52581.798"));
    }

    /**
     * senderolaser, criterio 159 ("ALM/REF/INS 12%"): subramo del cliente en [100,105,108] + item
     * 610030. Los decimales, otra vez, son los del ERP: la doc redondea 5456.9328 a 5456.93.
     */
    @Test
    void reproduceElCasoVerificadoDeSenderolaser() {
        valorizaYVerifica("senderolaser", "301", "610030", "1",
                new BigDecimal("6201.06"), new BigDecimal("12"), new BigDecimal("5456.9328"));
    }

    /**
     * LO QUE LA LISTA DE PRECIOS HACE, Y LO QUE NO. Verificado en vivo el 2026-10-07.
     *
     * El `CodigoListaPrecio` que mandamos por item **cambia el precio**: el mismo item y la misma
     * cantidad dan neto 63175 en la lista 2 y 59976 en la lista 3.
     *
     * Pero NO cambia que criterio aplica. El criterio 610 de dyssa tiene una condicion
     * ListaPrecioVenta=[2] y un escalon del 12% a partir de 45 unidades; el 611 es igual pero con
     * ListaPrecioVenta=[3] y el escalon en 150. Mandando lista 3 con 50 unidades sigue aplicando
     * el 610 (12%), no el 611 (que daria 5%). O sea: la condicion ListaPrecioVenta NO mira la
     * lista que mandamos, sino -- muy probablemente -- la que tiene asignada el cliente.
     *
     * Consecuencia practica: mandar la lista equivocada da el descuento correcto sobre el precio
     * EQUIVOCADO. El porcentaje engania porque es el mismo; el importe no.
     */
    @Test
    void laListaCambiaElPrecioPeroNoElCriterioQueAplica() {
        assumeHayCredenciales("dyssa");

        var conLista2 = valoriza("dyssa", "8380", "1000031861", "2", "50");
        var conLista3 = valoriza("dyssa", "8380", "1000031861", "3", "50");

        assertNotEquals(0, conLista2.neto().compareTo(conLista3.neto()),
                "la lista tiene que cambiar el precio");
        assertEquals(0, conLista2.descuento().compareTo(conLista3.descuento()),
                "pero no el porcentaje de descuento");
        assertEquals(conLista2.bonificaciones().get(0).id(), conLista3.bonificaciones().get(0).id(),
                "ni el criterio que lo otorga");
    }

    private LineaValorizada valoriza(String tenant, String cliente, String item, String lista,
                                     String cantidad) {
        var r = valorizador.valorizar(tenant, new PedidoAValorizar(cliente, lista,
                List.of(new ItemAValorizar(item, new BigDecimal(cantidad), "Unidad", BigDecimal.ONE, null))));
        return r.lineas().get(0);
    }

    private void assumeHayCredenciales(String tenant) {
        var d = configuracion.distribuidoras().get(tenant);
        assumeTrue(d != null && d.gescom() != null && d.gescom().usuario() != null
                && !d.gescom().usuario().isBlank(), "Sin credenciales de " + tenant);
    }

    private void valorizaYVerifica(String tenant, String cliente, String item, String lista,
                                   BigDecimal neto, BigDecimal descuento, BigDecimal conDescuento) {
        var distribuidora = configuracion.distribuidoras().get(tenant);
        assumeTrue(distribuidora != null && distribuidora.gescom() != null
                        && distribuidora.gescom().usuario() != null
                        && !distribuidora.gescom().usuario().isBlank(),
                "Sin credenciales de " + tenant + " en el entorno: se saltea");

        var pedido = new PedidoAValorizar(cliente, lista, List.of(
                new ItemAValorizar(item, new BigDecimal("6"), "Unidad", BigDecimal.ONE, null)));

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
