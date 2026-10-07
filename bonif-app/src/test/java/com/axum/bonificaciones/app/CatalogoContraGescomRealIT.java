package com.axum.bonificaciones.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras;
import com.axum.bonificaciones.app.gescom.CatalogoGescom;
import com.axum.bonificaciones.core.model.Criterio;
import com.axum.bonificaciones.core.model.TipoCondicion;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Corre contra TODAS las distribuidoras configuradas: cada una configura cosas distintas, y lo que
 * dyssa no usa senderolaser puede usarlo.
 *
 * Etiquetado "erp": excluido salvo -PincludeErpTests, y se saltea solo si no hay credenciales.
 */
@Tag("erp")
@SpringBootTest
class CatalogoContraGescomRealIT {

    @Autowired
    CatalogoGescom catalogo;

    @Autowired
    ConfiguracionDeDistribuidoras configuracion;

    private List<Criterio> criteriosDe(String tenant) {
        var distribuidora = configuracion.distribuidoras().get(tenant);
        assumeTrue(distribuidora != null && distribuidora.gescom() != null
                        && distribuidora.gescom().usuario() != null
                        && !distribuidora.gescom().usuario().isBlank(),
                "Sin credenciales de " + tenant + " en el entorno: se saltea");
        return catalogo.criterios(tenant);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dyssa", "senderolaser"})
    void elCatalogoRealNoTraeNingunTipoDeCondicionDesconocido(String tenant) {
        var desconocidas = criteriosDe(tenant).stream()
                .flatMap(c -> c.condiciones().stream())
                .filter(c -> c.tipo() == TipoCondicion.DESCONOCIDA)
                .map(c -> c.tipoCrudo() + " -> " + c.crudo())
                .distinct()
                .toList();

        assertEquals(List.of(), desconocidas,
                tenant + ": GESCOM tiene tipos de condicion que no mapeamos. Agregarlos a "
                        + "TipoCondicion y a CLAVE_DE_VALORES, no ignorarlos");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dyssa", "senderolaser"})
    void elCatalogoRealNoTraeNingunModificadorDesconocido(String tenant) {
        var desconocidos = criteriosDe(tenant).stream()
                .flatMap(c -> c.modificadores().stream())
                .filter(m -> m.noReconocido())
                .map(m -> m.tipo() + " -> " + m.crudo())
                .distinct()
                .toList();

        assertEquals(List.of(), desconocidos,
                tenant + ": GESCOM tiene modificadores que no sabemos interpretar. Mientras tanto "
                        + "el NUMERO sigue siendo correcto (lo da eval-pedido), pero no los "
                        + "podemos explicar");
    }

    /** Toda condicion que mira un atributo tiene que traer sus valores: si no, la clave cambio. */
    @ParameterizedTest
    @ValueSource(strings = {"dyssa", "senderolaser"})
    void ningunaCondicionConocidaQuedaSinValores(String tenant) {
        var vacias = criteriosDe(tenant).stream()
                .flatMap(c -> c.condicionesHoja().stream())
                .filter(c -> c.tipo() != TipoCondicion.DESCONOCIDA)
                .filter(c -> c.valores().isEmpty())
                .map(c -> c.tipoCrudo() + " -> " + c.crudo())
                .distinct()
                .toList();

        assertEquals(List.of(), vacias,
                tenant + ": cambio la clave que guarda los valores de algun tipo");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dyssa", "senderolaser"})
    void todoCriterioTraeSuCondicionRaizYAlMenosUnModificador(String tenant) {
        var criterios = criteriosDe(tenant);
        assertFalse(criterios.isEmpty(), tenant + ": el catalogo no puede venir vacio");

        var sinRaiz = criterios.stream()
                .filter(c -> c.codigoCondicionPrincipal() == null)
                .map(c -> c.id()).toList();
        assertEquals(List.of(), sinRaiz,
                tenant + ": sin codigoCondicionPrincipal no se sabe por donde evaluar");

        var sinModificadores = criterios.stream()
                .filter(c -> c.modificadores().isEmpty())
                .map(c -> c.id()).toList();
        assertEquals(List.of(), sinModificadores,
                tenant + ": un criterio sin modificadores no hace nada");
    }

    /**
     * EL TEST POR EL QUE HACE FALTA UNA SEGUNDA DISTRIBUIDORA.
     *
     * El cache de tokens esta indexado por tenant. Un bug que le devuelva a una distribuidora el
     * token de otra es invisible con una sola, y es el peor bug posible de este servicio: datos
     * comerciales de una distribuidora servidos a otra. Aca se piden los dos catalogos en la misma
     * JVM y se verifica que cada uno sea el suyo.
     */
    @Test
    void cadaDistribuidoraRecibeSuPropioCatalogoYNoElDeLaOtra() {
        var dyssa = criteriosDe("dyssa");
        var senderolaser = criteriosDe("senderolaser");

        assertFalse(dyssa.isEmpty());
        assertFalse(senderolaser.isEmpty());

        dyssa.forEach(c -> assertEquals("dyssa", c.distribuidora()));
        senderolaser.forEach(c -> assertEquals("senderolaser", c.distribuidora()));

        // Los nombres de los criterios son distintos entre distribuidoras: si se cruzaran los
        // tokens, un catalogo vendria con los criterios del otro.
        var nombresDyssa = dyssa.stream().map(Criterio::nombre).toList();
        var nombresSendero = senderolaser.stream().map(Criterio::nombre).toList();
        assertNotEquals(nombresDyssa, nombresSendero);

        assertTrue(dyssa.stream().anyMatch(c -> "558".equals(c.id())),
                "dyssa tiene que traer su criterio 558 (GANCIA CERO)");
        assertTrue(senderolaser.stream().anyMatch(c -> "159".equals(c.id())),
                "senderolaser tiene que traer su criterio 159 (ALM/REF/INS 12%)");
        assertFalse(senderolaser.stream().anyMatch(c -> nombresDyssa.contains(c.nombre())
                        && c.nombre() != null && c.nombre().contains("GANCIA")),
                "senderolaser no puede traer criterios de dyssa");
    }
}
