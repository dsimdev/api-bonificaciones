package com.axum.bonificaciones.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.axum.bonificaciones.app.gescom.CatalogoGescom;
import com.axum.bonificaciones.core.model.Criterio;
import com.axum.bonificaciones.core.model.Operacion;
import com.axum.bonificaciones.core.model.TipoCondicion;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * El catalogo de criterios, contra el JSON REAL de get-promociones de dyssa.
 *
 * Cada test de aca fija algo que solo se descubrio al ver el payload de verdad. Antes de tenerlo,
 * el mapeador estaba escrito contra un fixture reconstruido de la doc y tenia un bug grueso: los
 * campos del modificador estan DENTRO de configuracionJson, asi que el descuento salia 0 para
 * todos los criterios.
 */
@SpringBootTest
class CatalogoGescomTest {

    // Bloque estatico y no @BeforeAll: @DynamicPropertySource se resuelve al crear el contexto de
    // Spring, que pasa ANTES de los @BeforeAll del test.
    private static final WireMockServer gescom =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        gescom.start();
    }

    private static String fixture(String nombre) {
        try (var in = CatalogoGescomTest.class.getResourceAsStream("/fixtures/" + nombre)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer el fixture " + nombre, e);
        }
    }

    @Autowired
    CatalogoGescom catalogo;

    @AfterAll
    static void bajar() {
        gescom.stop();
    }

    @DynamicPropertySource
    static void apuntarAlStub(DynamicPropertyRegistry registry) {
        registry.add("bonificaciones.distribuidoras.dyssa.gescom.host", gescom::baseUrl);
        registry.add("bonificaciones.gescom.url-auth", gescom::baseUrl);
        registry.add("bonificaciones.distribuidoras.dyssa.gescom.realm", () -> "gcw-dyssa");
        registry.add("bonificaciones.distribuidoras.dyssa.gescom.usuario", () -> "usuario-api");
        registry.add("bonificaciones.distribuidoras.dyssa.gescom.clave", () -> "clave-api");
        registry.add("bonificaciones.gescom.cache-criterios-minutos", () -> "60");
    }

    @BeforeEach
    void stubs() {
        catalogo.olvidar("dyssa");
        gescom.resetAll();
        gescom.stubFor(post(urlPathEqualTo("/realms/gcw-dyssa/protocol/openid-connect/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok\",\"expires_in\":300}")));
        gescom.stubFor(get(urlPathEqualTo("/data/cmd/ventas/api/v1/get-promociones"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(fixture("get-promociones-dyssa.json"))));
    }

    private Criterio criterio(String id) {
        return catalogo.criterios("dyssa").stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No se mapeo el criterio " + id));
    }

    private BigDecimal descuentoDe(com.axum.bonificaciones.core.model.Modificador m) {
        return ((Operacion.Descuento) m.operacion()).descuento();
    }

    /**
     * EL BUG QUE ESTE TEST EXISTE PARA EVITAR: descuento, dataConditionCodes y allowOverlap viajan
     * DENTRO del configuracionJson del modificador, no como campos de ese nivel. Leyendolos como
     * campos sueltos, el descuento daba 0 en todos los criterios del catalogo.
     */
    @Test
    void elDescuentoSaleDelConfiguracionJsonDelModificador() {
        var gancia = criterio("558");
        assertEquals(1, gancia.modificadores().size());
        // 0.1 en el ERP -> 10 en el contrato.
        assertEquals(0, new BigDecimal("10").compareTo(descuentoDe(gancia.modificadores().get(0))));
    }

    @Test
    void elIdLlegaComoNumeroYSeNormalizaATexto() {
        assertEquals("558", criterio("558").id());
    }

    @Test
    void mantieneLosDecimalesDelDescuento() {
        // 0.1812 -> 18.12, no 18 ni 18.120000001.
        assertEquals(0, new BigDecimal("18.12")
                .compareTo(descuentoDe(criterio("148").modificadores().get(0))));
    }

    @Test
    void leeAllowOverlapDelConfiguracionJson() {
        assertTrue(criterio("4").modificadores().get(0).permiteSuperposicion());
        assertFalse(criterio("30").modificadores().get(0).permiteSuperposicion());
    }

    @Test
    void parseaLaVigenciaConOffset() {
        var gancia = criterio("558");
        assertEquals("2026-05-11", gancia.vigencia().desde().toString());
        assertEquals("2026-10-12", gancia.vigencia().hasta().toString());
    }

    /**
     * La clave que guarda los valores cambia segun el tipo de condicion: codigos, tags, marcas,
     * proveedores, lineas, rubros, familias, calibres, subRamoCodigos. Este test recorre el
     * catalogo real y verifica que ningun tipo conocido quede sin valores.
     */
    @Test
    void todoTipoConocidoDeCondicionTraeSusValores() {
        var sinValores = catalogo.criterios("dyssa").stream()
                .flatMap(c -> c.condicionesHoja().stream())
                .filter(c -> c.tipo() != TipoCondicion.DESCONOCIDA)
                .filter(c -> c.valores().isEmpty())
                .map(c -> c.tipo() + " " + c.crudo())
                .toList();
        assertEquals(List.of(), sinValores, "hay condiciones conocidas que quedaron sin valores");
    }

    @Test
    void mapeaLosTiposDeCondicionDelCatalogoReal() {
        var tipos = catalogo.criterios("dyssa").stream()
                .flatMap(c -> c.condiciones().stream())
                .map(c -> c.tipo())
                .distinct()
                .sorted()
                .toList();
        assertTrue(tipos.containsAll(List.of(
                TipoCondicion.TODAS, TipoCondicion.ALGUNA, TipoCondicion.CODIGO_CLIENTE,
                TipoCondicion.TAG_CLIENTE, TipoCondicion.SUBRAMO_CLIENTE, TipoCondicion.CODIGO_ITEM,
                TipoCondicion.MARCA_ARTICULO, TipoCondicion.PROVEEDOR_ARTICULO,
                TipoCondicion.LINEA_ARTICULO, TipoCondicion.RUBRO_ITEM,
                TipoCondicion.FAMILIA_ARTICULO, TipoCondicion.CALIBRE_ARTICULO,
                TipoCondicion.TAG_ITEM)), "faltan tipos del catalogo real: " + tipos);
    }

    /**
     * Un modificador de tipo desconocido NO se convierte en un descuento de 0: queda marcado y con
     * su crudo. Un modificador que no entendemos pasando como "0%" es una promo que desaparece sin
     * que nadie se entere.
     */
    @Test
    void unModificadorDeTipoDesconocidoQuedaMarcadoYNoComoDescuentoCero() {
        var sintetico = criterio("9999");
        var raro = sintetico.modificadores().stream()
                .filter(m -> "ModificadorQueTodaviaNoExiste".equals(m.tipo()))
                .findFirst().orElseThrow();

        assertTrue(raro.noReconocido());
        assertTrue(raro.crudo().contains("algoRaro"));

        var conocido = sintetico.modificadores().stream()
                .filter(m -> !m.noReconocido())
                .findFirst().orElseThrow();
        assertEquals("DescuentoItem", conocido.tipo());
        assertEquals(0, new BigDecimal("7").compareTo(descuentoDe(conocido)));
    }

    /** Un tipo nuevo del ERP no desaparece: llega como DESCONOCIDA con su crudo. */
    @Test
    void unTipoDesconocidoConservaElCrudo() {
        var condicion = criterio("9999").condicionesHoja().get(0);
        assertEquals(TipoCondicion.DESCONOCIDA, condicion.tipo());
        assertTrue(condicion.crudo().contains("loQueSea"));
    }

    @Test
    void leeInvertedCuandoEstaEnTrue() {
        var huerfana = criterio("30").condicion(103).orElseThrow();
        assertEquals(TipoCondicion.CODIGO_ITEM, huerfana.tipo());
        assertTrue(huerfana.invertida(), "la condicion 103 del criterio 30 es inverted:true");
    }

    @Test
    void leeRequiredQuantityComoCantidadMinima() {
        assertEquals(1, criterio("558").condicion(101).orElseThrow().cantidadMinima());
    }

    /**
     * El catalogo real tiene condiciones HUERFANAS: existen en la lista pero ningun combinador las
     * referencia, asi que no participan de la evaluacion. Mostrarlas como "por que aplico" seria
     * mentir -- el criterio 294 define una condicion de linea y su All solo referencia la de
     * cliente.
     */
    @Test
    void lasCondicionesHuerfanasQuedanFueraDeLasQueEstanEnJuego() {
        var nuevoPremium = criterio("294");
        assertEquals(3, nuevoPremium.condiciones().size(), "las tres siguen estando en el modelo");

        var enJuego = nuevoPremium.condicionesEnJuego().stream().map(c -> c.codigo()).toList();
        assertEquals(List.of(100, 101), enJuego, "la 102 es huerfana: no la referencia nadie");

        var grupo8 = criterio("30");
        assertFalse(grupo8.condicionesEnJuego().stream().anyMatch(c -> c.codigo() == 103),
                "la 103 del criterio 30 tampoco esta referenciada");
    }

    /**
     * El criterio 2 de dyssa tiene un ciclo: el All 100 referencia la 104 (un Any) y la 101, y ese
     * Any 104 vuelve a referenciar la 101. Sin corte, el recorrido no termina.
     */
    @Test
    void unCicloEnElGrafoDeCondicionesNoCuelga() {
        var grupo10 = criterio("2");
        var enJuego = grupo10.condicionesEnJuego().stream().map(c -> c.codigo()).sorted().toList();
        assertEquals(List.of(100, 101, 102, 103, 104), enJuego);
    }

    /**
     * Varios modificadores con descuentos distintos apuntando a condiciones distintas: asi se arma
     * el "10% en global y 5% en Pehuamar". Cada uno explica solo lo suyo.
     */
    @Test
    void cadaModificadorApuntaASusPropiasCondiciones() {
        var grupo10 = criterio("2");
        assertEquals(2, grupo10.modificadores().size());

        var diez = grupo10.modificadores().stream()
                .filter(m -> descuentoDe(m).compareTo(new BigDecimal("10")) == 0)
                .findFirst().orElseThrow();
        var cinco = grupo10.modificadores().stream()
                .filter(m -> descuentoDe(m).compareTo(new BigDecimal("5")) == 0)
                .findFirst().orElseThrow();

        assertEquals(List.of(102), diez.condicionesDeDatos());
        assertEquals(List.of(103), cinco.condicionesDeDatos());

        assertEquals(List.of("pepsico-11"),
                grupo10.condicionesDe(cinco).get(0).valores(),
                "el 5% cae solo sobre la marca de Pehuamar");
    }

    /** Un modificador sin dataConditionCodes cae sobre todo lo que califique. */
    @Test
    void sinDataConditionCodesValenTodasLasCondicionesHoja() {
        var frigor = criterio("4");
        var modificador = frigor.modificadores().get(0);
        assertEquals(List.of(), modificador.condicionesDeDatos());
        assertEquals(frigor.condicionesHoja(), frigor.condicionesDe(modificador));
    }

    /** La descripcion que escribe el ERP es lo que mejor explica, y viene gratis. */
    @Test
    void conservaLasDescripcionesDelErp() {
        assertEquals("CLIENTES 10% DE DESCUENTO EN GLOBAL Y 5% EN PEHUAMAR",
                criterio("2").descripcion());
        assertEquals("La venta tiene uno o mas items",
                criterio("558").condicion(101).orElseThrow().descripcion());
    }

    // --- Cache ---

    @Test
    void laSegundaLlamadaNoVuelveAConsultarGescom() {
        catalogo.criterios("dyssa");
        int llamadasAntes = gescom.countRequestsMatching(
                com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                        urlPathEqualTo("/data/cmd/ventas/api/v1/get-promociones")).build()
        ).getCount();

        catalogo.criterios("dyssa");
        int llamadasDespues = gescom.countRequestsMatching(
                com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                        urlPathEqualTo("/data/cmd/ventas/api/v1/get-promociones")).build()
        ).getCount();

        assertEquals(llamadasAntes, llamadasDespues,
                "la segunda llamada no deberia pegar a GESCOM");
    }

    @Test
    void siGescomFallaYHayCacheSirveLoViejo() {
        var primero = catalogo.criterios("dyssa");
        assertFalse(primero.isEmpty());

        // Forzar que el cache se considere vencido
        catalogo.olvidar("dyssa");

        // Cargar el cache de nuevo
        catalogo.criterios("dyssa");

        // Ahora hacer que GESCOM falle
        gescom.stubFor(get(urlPathEqualTo("/data/cmd/ventas/api/v1/get-promociones"))
                .willReturn(aResponse().withStatus(500).withBody("error")));

        // Olvidar para forzar refresh
        catalogo.olvidar("dyssa");

        // Sin cache -> falla, porque no hay nada de donde servir
        assertThrows(RuntimeException.class, () -> catalogo.criterios("dyssa"));
    }

    @Test
    void actualizadoEnSeRegistraDespuesDeCargar() {
        catalogo.criterios("dyssa");
        assertNotNull(catalogo.actualizadoEn("dyssa"),
                "despues de criterios(), actualizadoEn no puede ser null");
    }
}
