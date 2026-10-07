package com.axum.bonificaciones.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axum.bonificaciones.app.gescom.CatalogoGescom;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** GET /v1/{tenant}/criterios contra el fixture REAL del catalogo de dyssa. */
@SpringBootTest
@AutoConfigureMockMvc
class CriteriosControllerTest {

    // Bloque estatico y no @BeforeAll: @DynamicPropertySource se resuelve al crear el contexto de
    // Spring, que pasa ANTES de los @BeforeAll del test.
    private static final WireMockServer gescom =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        gescom.start();
    }

    private static String fixture(String nombre) {
        try (var in = CriteriosControllerTest.class.getResourceAsStream("/fixtures/" + nombre)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer el fixture " + nombre, e);
        }
    }

    @Autowired
    MockMvc mockMvc;

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
        registry.add("bonificaciones.distribuidoras.dyssa.gescom.usuario", () -> "u");
        registry.add("bonificaciones.distribuidoras.dyssa.gescom.clave", () -> "p");
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

    /** El criterio 558 vence el 2026-10-12, asi que a esa fecha todavia esta. */
    @Test
    void devuelveElCriterioConSusCondicionesYSuDescuentoEnPorcentaje() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/criterios?fecha=2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fuente").value("GESCOM"))
                .andExpect(jsonPath("$.tenant").value("dyssa"))
                .andExpect(jsonPath("$.criterios[?(@.id == '558')].nombre")
                        .value("GANCIA CERO - 14792"))
                .andExpect(jsonPath("$.criterios[?(@.id == '558')].vigenteHasta")
                        .value("2026-10-12"))
                .andExpect(jsonPath("$.criterios[?(@.id == '558')].condiciones[0].tipo")
                        .value("CODIGO_ITEM"))
                .andExpect(jsonPath("$.criterios[?(@.id == '558')].condiciones[0].descripcion")
                        .value("La venta tiene uno o mas items"))
                .andExpect(jsonPath("$.criterios[?(@.id == '558')].bonificaciones[0].tipo")
                        .value("DESCUENTO"))
                .andExpect(jsonPath("$.criterios[?(@.id == '558')].bonificaciones[0].descuento")
                        .value(10.0));
    }

    /**
     * La vigencia la filtra el endpoint por defecto: un criterio vencido no viaja en el payload
     * para que el consumidor lo descarte. El 558 vence el 2026-10-12.
     */
    @Test
    void noDevuelveLosVencidos() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/criterios?fecha=2026-11-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterios[?(@.id == '558')]").isEmpty());
    }

    /** Pero quien los quiera, los pide. */
    @Test
    void losVencidosSePidenExplicitamente() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/v1/dyssa/criterios?fecha=2026-11-01&incluirNoVigentes=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterios[?(@.id == '558')].nombre")
                        .value("GANCIA CERO - 14792"));
    }

    /**
     * Un criterio con dos modificadores apuntando a condiciones distintas: el "10% en global y 5%
     * en Pehuamar". Cada bonificacion dice a que condiciones cae.
     */
    @Test
    void cadaBonificacionDiceAQueCondicionesCae() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/criterios?fecha=2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterios[?(@.id == '2')].bonificaciones.length()")
                        .value(2))
                .andExpect(jsonPath(
                        "$.criterios[?(@.id == '2')].bonificaciones[?(@.descuento == 5)].aplicaA[0].valores[0]")
                        .value("pepsico-11"));
    }

    /** El total acompania a la lista: no hay que contarla del otro lado. */
    @Test
    void informaElTotal() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/v1/dyssa/criterios?fecha=2026-10-01&incluirNoVigentes=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.criterios.length()").value(12));
    }

    /**
     * Un modificador que no sabemos interpretar NO se oculta ni se disfraza de descuento 0: sale
     * como NO_RECONOCIDO con el tipo que le da el ERP y su crudo.
     */
    @Test
    void unModificadorDesconocidoSeVeComoTal() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/v1/dyssa/criterios?fecha=2026-10-01&incluirNoVigentes=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.criterios[?(@.id == '9999')].bonificaciones[?(@.tipo == 'NO_RECONOCIDO')].tipoEnElErp")
                        .value("ModificadorQueTodaviaNoExiste"));
    }

    @Test
    void unTenantDesconocidoFallaConCodigoDeDominio() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/noexiste/criterios"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TENANT_DESCONOCIDO"));
    }
}
