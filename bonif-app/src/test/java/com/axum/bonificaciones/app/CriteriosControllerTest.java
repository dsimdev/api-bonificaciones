package com.axum.bonificaciones.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axum.bonificaciones.app.gescom.ArticulosGescom;
import com.axum.bonificaciones.app.gescom.CatalogoGescom;
import com.axum.bonificaciones.app.gescom.ClientesGescom;
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

    @Autowired MockMvc mockMvc;
    @Autowired CatalogoGescom catalogo;
    @Autowired ArticulosGescom articulos;
    @Autowired ClientesGescom clientes;

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
        registry.add("bonificaciones.gescom.cache-criterios-minutos", () -> "60");
    }

    @BeforeEach
    void stubs() {
        catalogo.olvidar("dyssa");
        articulos.olvidar("dyssa");
        clientes.olvidar("dyssa");
        gescom.resetAll();
        gescom.stubFor(post(urlPathEqualTo("/realms/gcw-dyssa/protocol/openid-connect/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok\",\"expires_in\":300}")));
        gescom.stubFor(get(urlPathEqualTo("/data/cmd/ventas/api/v1/get-promociones"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(fixture("get-promociones-dyssa.json"))));
        gescom.stubFor(get(urlPathEqualTo("/data/cmd/inventario/api/v1/get-articulos"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(fixture("get-articulos-dyssa.json"))));
        gescom.stubFor(get(urlPathEqualTo("/data/cmd/ventas/api/v1/get-clientes"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(fixture("get-clientes-dyssa.json"))));
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

    @Test
    void noDevuelveLosVencidos() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/criterios?fecha=2026-11-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterios[?(@.id == '558')]").isEmpty());
    }

    @Test
    void losVencidosSePidenExplicitamente() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/v1/dyssa/criterios?fecha=2026-11-01&incluirNoVigentes=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterios[?(@.id == '558')].nombre")
                        .value("GANCIA CERO - 14792"));
    }

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

    @Test
    void informaElTotal() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/v1/dyssa/criterios?fecha=2026-10-01&incluirNoVigentes=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.criterios.length()").value(12));
    }

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
    void laRespuestaIncluyeActualizadoEn() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/criterios?fecha=2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualizadoEn").isNotEmpty())
                .andExpect(jsonPath("$.consultadoEn").isNotEmpty());
    }

    @Test
    void unTenantDesconocidoFallaConCodigoDeDominio() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/noexiste/criterios"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TENANT_DESCONOCIDO"));
    }

    // --- Articulos resueltos ---

    /** El criterio 558 tiene CodigoItem "5000014792", que existe en el fixture de articulos. */
    @Test
    void elCriterioTraeArticulosResueltos() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/criterios?fecha=2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterios[?(@.id == '558')].articulos[0]")
                        .value("5000014792"));
    }

    /**
     * El criterio 2 tiene TODAS(TagCliente, ALGUNA(tag, marca-global, marca-pehuamar),
     * marca-global, marca-pehuamar). La interseccion del TODAS sobre condiciones de articulo
     * reduce a los que cumplen TODAS: marca-global({pepsi-001, pepsi-002, pehuamar-001}) ∩
     * marca-pehuamar({pehuamar-001}) = {ART-PEHUAMAR-001}. Ese es el unico que cumple todo.
     */
    @Test
    void losArticulosSeResuelvenPorMarcaConElIndice() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/criterios?fecha=2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterios[?(@.id == '2')].articulos[0]")
                        .value("ART-PEHUAMAR-001"));
    }

    // --- Filtro por cliente ---

    /**
     * El criterio 2 tiene TagCliente = GRUPO 10. El cliente 8380 tiene ese tag => lo recibe.
     */
    @Test
    void conClienteFiltraLosCriteriosQueLeAplican() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/v1/dyssa/criterios?fecha=2026-10-01&cliente=8380"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterios[?(@.id == '2')]").isNotEmpty());
    }

    /**
     * El cliente 1001 NO tiene el tag GRUPO 10 => no recibe el criterio 2.
     * Pero si recibe los criterios sin condiciones de cliente (558 no pide tag ni cliente).
     */
    @Test
    void clienteSinTagNoRecibeElCriterioConTag() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/v1/dyssa/criterios?fecha=2026-10-01&cliente=1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterios[?(@.id == '2')]").isEmpty())
                .andExpect(jsonPath("$.criterios[?(@.id == '558')]").isNotEmpty());
    }

    // --- aplicaATodo ---

    /** El criterio 558 tiene CodigoItem: aplicaATodo = false y articulos con el codigo. */
    @Test
    void criterioConCondicionDeArticuloNoAplicaATodo() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/criterios?fecha=2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterios[?(@.id == '558')].aplicaATodo").value(false))
                .andExpect(jsonPath("$.criterios[?(@.id == '558')].articulos[0]")
                        .value("5000014792"));
    }

    /**
     * El criterio 294 solo tiene CodigoCliente, sin condicion de articulo: aplicaATodo = true
     * y articulos vacio.
     */
    @Test
    void criterioSinCondicionDeArticuloAplicaATodo() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/criterios?fecha=2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterios[?(@.id == '294')].aplicaATodo").value(true));
    }

    /** Sin ?cliente=, devuelve todos (no filtra). */
    @Test
    void sinClienteDevuelveTodos() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/criterios?fecha=2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterios[?(@.id == '2')]").isNotEmpty())
                .andExpect(jsonPath("$.criterios[?(@.id == '558')]").isNotEmpty());
    }
}
