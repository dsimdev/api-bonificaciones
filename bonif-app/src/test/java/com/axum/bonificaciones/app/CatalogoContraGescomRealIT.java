package com.axum.bonificaciones.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras;
import com.axum.bonificaciones.app.gescom.CatalogoGescom;
import com.axum.bonificaciones.core.model.TipoCondicion;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * El informe de parseo del catalogo REAL: el criterio de salida de la Fase 1.
 *
 * No verifica valores puntuales sino que **no se nos escape nada**. Es la red que avisa cuando
 * GESCOM agrega un tipo de condicion o de modificador que no sabemos interpretar -- que ya paso:
 * asi aparecieron ListaPrecioVenta, TablaDescuentoItem y AgregaGratis, ninguno de los cuales
 * estaba en la doc de referencia.
 *
 * Etiquetado "erp": excluido salvo -PincludeErpTests, y se saltea solo si no hay credenciales.
 */
@Tag("erp")
@SpringBootTest
class CatalogoContraGescomRealIT {

    private static final String TENANT = "dyssa";

    @Autowired
    CatalogoGescom catalogo;

    @Autowired
    ConfiguracionDeDistribuidoras configuracion;

    @org.junit.jupiter.api.BeforeEach
    void hayCredenciales() {
        var distribuidora = configuracion.distribuidoras().get(TENANT);
        assumeTrue(distribuidora != null && distribuidora.gescom() != null
                        && distribuidora.gescom().usuario() != null
                        && !distribuidora.gescom().usuario().isBlank(),
                "Sin credenciales de " + TENANT + " en el entorno: se saltea");
    }

    @Test
    void elCatalogoRealNoTraeNingunTipoDeCondicionDesconocido() {
        var desconocidas = catalogo.criterios(TENANT).stream()
                .flatMap(c -> c.condiciones().stream())
                .filter(c -> c.tipo() == TipoCondicion.DESCONOCIDA)
                .map(c -> c.tipoCrudo() + " -> " + c.crudo())
                .distinct()
                .toList();

        assertEquals(List.of(), desconocidas,
                "GESCOM tiene tipos de condicion que no mapeamos. Agregarlos a TipoCondicion y a "
                        + "CLAVE_DE_VALORES, no ignorarlos");
    }

    @Test
    void elCatalogoRealNoTraeNingunModificadorDesconocido() {
        var desconocidos = catalogo.criterios(TENANT).stream()
                .flatMap(c -> c.modificadores().stream())
                .filter(m -> m.noReconocido())
                .map(m -> m.tipo() + " -> " + m.crudo())
                .distinct()
                .toList();

        assertEquals(List.of(), desconocidos,
                "GESCOM tiene modificadores que no sabemos interpretar. Mientras tanto el NUMERO "
                        + "sigue siendo correcto (lo da eval-pedido), pero no los podemos explicar");
    }

    /** Toda condicion que mira un atributo tiene que traer sus valores: si no, la clave cambio. */
    @Test
    void ningunaCondicionConocidaQuedaSinValores() {
        var vacias = catalogo.criterios(TENANT).stream()
                .flatMap(c -> c.condicionesHoja().stream())
                .filter(c -> c.tipo() != TipoCondicion.DESCONOCIDA)
                .filter(c -> c.valores().isEmpty())
                .map(c -> c.tipoCrudo() + " -> " + c.crudo())
                .distinct()
                .toList();

        assertEquals(List.of(), vacias, "cambio la clave que guarda los valores de algun tipo");
    }

    @Test
    void todoCriterioTraeSuCondicionRaizYAlMenosUnModificador() {
        var criterios = catalogo.criterios(TENANT);
        assertFalse(criterios.isEmpty(), "el catalogo no puede venir vacio");

        var sinRaiz = criterios.stream()
                .filter(c -> c.codigoCondicionPrincipal() == null)
                .map(c -> c.id()).toList();
        assertEquals(List.of(), sinRaiz, "sin codigoCondicionPrincipal no se sabe por donde evaluar");

        var sinModificadores = criterios.stream()
                .filter(c -> c.modificadores().isEmpty())
                .map(c -> c.id()).toList();
        assertEquals(List.of(), sinModificadores, "un criterio sin modificadores no hace nada");
    }
}
