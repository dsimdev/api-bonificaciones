package com.axum.bonificaciones.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * El camino completo contra un GESCOM simulado: token, eval-pedido, catalogo y respuesta.
 *
 * WireMock y no un mock en proceso a proposito: los bugs que importan aca viven en el ensamblado
 * -- el header del token, el Pedido envuelto, CodigoItem vs CodigoArticulo, el form-urlencoded de
 * Keycloak. Un mock del cliente HTTP no atrapa nada de eso.
 *
 * OJO: los fixtures de get-promociones estan RECONSTRUIDOS a partir de la doc, no capturados de
 * la API real -- nunca vimos una respuesta de ese endpoint. El de eval-pedido si refleja un caso
 * verificado en vivo (dyssa, criterio 558).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ValorizacionGescomTest {

    // Bloque estatico y no @BeforeAll: @DynamicPropertySource se resuelve al crear el contexto
    // de Spring, que pasa ANTES de los @BeforeAll del test. Si el server no esta arriba ahi, la
    // app queda apuntando al host real de la distribuidora.
    private static final WireMockServer gescom =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        gescom.start();
    }

    private static String fixture(String nombre) {
        try (var in = ValorizacionGescomTest.class.getResourceAsStream("/fixtures/" + nombre)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer el fixture " + nombre, e);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    com.axum.bonificaciones.app.gescom.CatalogoGescom catalogo;

    @Autowired
    com.axum.bonificaciones.app.gescom.ServicioDeToken tokens;

    @AfterAll
    static void bajarGescom() {
        gescom.stop();
    }

    @DynamicPropertySource
    static void apuntarAlStub(DynamicPropertyRegistry registry) {
        registry.add("bonificaciones.distribuidoras.dyssa.gescom.host", gescom::baseUrl);
        registry.add("bonificaciones.gescom.url-auth", gescom::baseUrl);
        registry.add("bonificaciones.distribuidoras.dyssa.gescom.realm", () -> "gcw-dyssa");
        registry.add("bonificaciones.distribuidoras.dyssa.gescom.usuario", () -> "usuario-api");
        registry.add("bonificaciones.distribuidoras.dyssa.gescom.clave", () -> "clave-api");
    }

    @BeforeEach
    void stubs() {
        // Los caches viven en el contexto de Spring, que es el mismo para toda la clase: sin esto
        // un test reusa el catalogo que trajo el anterior y los stubs de este no se llaman nunca.
        catalogo.olvidar("dyssa");
        tokens.olvidar("dyssa");
        gescom.resetAll();
        gescom.stubFor(post(urlPathEqualTo("/realms/gcw-dyssa/protocol/openid-connect/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok-dyssa\",\"expires_in\":300}")));
        gescom.stubFor(post(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(fixture("eval-pedido-dyssa.json"))));
        gescom.stubFor(get(urlPathEqualTo("/data/cmd/ventas/api/v1/get-promociones"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(fixture("get-promociones-dyssa.json"))));
    }

    private static final String PEDIDO = """
            {"cliente":"8380","listaPrecio":"2",
             "items":[{"codigo":"5000014792","cantidad":6,"unidad":"Unidad","unidadFactor":1}]}
            """;

    /**
     * El caso verificado en vivo en dyssa: neto 58424.22, 10% de descuento, 52581.80.
     *
     * El descuento sale 10 y no 0.1: GESCOM lo da como fraccion y el conector lo pasa a
     * porcentaje, que es la convencion del contrato.
     */
    @Test
    void valorizaElCasoRealDeDyssaYDevuelveElDescuentoEnPorcentaje() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fuente").value("GESCOM"))
                .andExpect(jsonPath("$.calculadoPor").value("ERP"))
                .andExpect(jsonPath("$.lineas[0].codigo").value("5000014792"))
                .andExpect(jsonPath("$.lineas[0].neto").value(58424.22))
                .andExpect(jsonPath("$.lineas[0].descuento").value(10.0))
                .andExpect(jsonPath("$.lineas[0].netoConDescuento").value(52581.80))
                .andExpect(jsonPath("$.lineas[0].creadaPorPromo").value(false));
    }

    /** El catalogo aporta el "por que": que condicion disparo el descuento. */
    @Test
    void enriqueceLaBonificacionConLasCondicionesDelCatalogo() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineas[0].bonificaciones[0].id").value("558"))
                .andExpect(jsonPath("$.lineas[0].bonificaciones[0].nombre")
                        .value("GANCIA CERO - 14792"))
                .andExpect(jsonPath("$.lineas[0].bonificaciones[0].descuento").value(10.0))
                .andExpect(jsonPath("$.lineas[0].bonificaciones[0].condiciones[0].tipo")
                        .value("CODIGO_ITEM"))
                .andExpect(jsonPath("$.lineas[0].bonificaciones[0].condiciones[0].valores[0]")
                        .value("5000014792"));
    }

    /** El item va como CodigoItem. Con CodigoArticulo GESCOM lo ignora en silencio. */
    @Test
    void mandaCodigoItemYNoCodigoArticulo() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                .contentType(MediaType.APPLICATION_JSON).content(PEDIDO));

        gescom.verify(postRequestedFor(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido"))
                .withRequestBody(containing("\"CodigoItem\":\"5000014792\""))
                .withRequestBody(notMatching("(?s).*CodigoArticulo.*")));
    }

    /** El GUID lo generamos nosotros: un reintento no depende del consumidor. */
    @Test
    void generaElIdentificadorDelPedido() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                .contentType(MediaType.APPLICATION_JSON).content(PEDIDO));

        gescom.verify(postRequestedFor(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido"))
                .withRequestBody(containing("\"Identificador\":")));
    }

    @Test
    void mandaElTokenEnTodasLasLlamadasAlGateway() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                .contentType(MediaType.APPLICATION_JSON).content(PEDIDO));

        gescom.verify(postRequestedFor(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido"))
                .withHeader("Authorization", equalTo("Bearer tok-dyssa")));
        gescom.verify(getRequestedFor(urlPathEqualTo("/data/cmd/ventas/api/v1/get-promociones"))
                .withHeader("Authorization", equalTo("Bearer tok-dyssa")));
    }

    /**
     * El catalogo sirve para explicar, no para calcular: si se cae, la valorizacion igual sale.
     * Voltear un checkout porque no pudimos traer el "por que" seria cambiar un lujo por la venta.
     */
    @Test
    void siElCatalogoFallaLaValorizacionIgualResponde() throws Exception {
        gescom.stubFor(get(urlPathEqualTo("/data/cmd/ventas/api/v1/get-promociones"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineas[0].descuento").value(10.0))
                .andExpect(jsonPath("$.lineas[0].bonificaciones[0].id").value("558"))
                .andExpect(jsonPath("$.lineas[0].bonificaciones[0].condiciones").isEmpty());
    }

    @Test
    void siGescomNoRespondeDevuelve503ConCodigoDeDominio() throws Exception {
        gescom.stubFor(post(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido"))
                .willReturn(aResponse().withStatus(503).withBody("")));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("FUENTE_NO_DISPONIBLE"));
    }

    /** Una ruta no registrada en el gateway de comandos es un error NUESTRO, no de la distri. */
    @Test
    void unComandoInexistenteSeDistingueDeUnErrorDeLaFuente() throws Exception {
        gescom.stubFor(post(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido"))
                .willReturn(aResponse().withStatus(500)
                        .withBody("Unity.Exceptions.InvalidRegistrationException: no such route")));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.codigo").value("COMANDO_INEXISTENTE"));
    }

    /**
     * Un cliente que no existe es error de LA TIENDA, no del ERP: 400, no 502. GESCOM tapa los
     * errores de runtime con un generico, pero la validacion de modelo si informa, y cuando
     * informa hay que creerle. Devolver 502 seria decirle "reintenta" a algo que nunca va a andar.
     */
    @Test
    void unClienteInexistenteEsErrorDeLaTiendaNoDelErp() throws Exception {
        gescom.stubFor(post(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido"))
                .willReturn(aResponse().withStatus(400).withBody(
                        "{\"errorCode\":\"0\",\"message\":\"El campo CodigoCliente tiene un codigo invalido\"}")));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_INEXISTENTE"))
                .andExpect(jsonPath("$.mensaje")
                        .value("El campo CodigoCliente tiene un codigo invalido"));
    }

    /** Cualquier otra validacion de modelo tambien es del consumidor. */
    @Test
    void otraValidacionDeModeloTambienEs400() throws Exception {
        gescom.stubFor(post(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido"))
                .willReturn(aResponse().withStatus(400).withBody(
                        "{\"errorCode\":\"0\",\"message\":\"No se ha especificado la cantidad\"}")));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PEDIDO_RECHAZADO_POR_LA_FUENTE"));
    }

    /** El generico de GESCOM se devuelve con el crudo adjunto, no se traga. */
    @Test
    void elErrorGenericoDeGescomLlegaConSuCrudo() throws Exception {
        gescom.stubFor(post(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"errorCode\":\"0\",\"message\":\"Error desconocido\"}")));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.codigo").value("FUENTE_ERROR_DESCONOCIDO"))
                .andExpect(jsonPath("$.crudo").value(
                        "{\"errorCode\":\"0\",\"message\":\"Error desconocido\"}"));
    }

    /** El total viene calculado: la tienda no suma y no diverge por redondeo. */
    @Test
    void devuelveLosTotalesDelPedido() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totales.neto").value(58424.22))
                .andExpect(jsonPath("$.totales.netoConDescuento").value(52581.80))
                .andExpect(jsonPath("$.totales.descuento").value(5842.42));
    }

    /** La referencia de la tienda vuelve tal cual, para poder rastrear despues. */
    @Test
    void devuelveLaReferenciaDeLaTienda() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cliente":"8380","listaPrecio":"2","referencia":"carrito-991",
                                 "items":[{"codigo":"5000014792","cantidad":6}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referencia").value("carrito-991"));
    }

    /** Con lista, no hay nada que suponer. */
    @Test
    void sinSupuestosCuandoElPedidoViencompleto() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supuestos").isEmpty());
    }

    /**
     * Sin lista de precios el ERP usa la del cliente. No es un error, pero cambia el PRECIO, asi
     * que sale marcado: nunca un default silencioso.
     */
    @Test
    void sinListaDePrecioLoAvisaEnSupuestos() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cliente\":\"8380\",\"items\":[{\"codigo\":\"5000014792\",\"cantidad\":6}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supuestos[0].codigo").value("LISTA_PRECIO_NO_ENVIADA"));
    }

    @Test
    void unTenantQueNoEstaConfiguradoFallaAntesDeTocarLaRed() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/noexiste/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("TENANT_DESCONOCIDO"));

        gescom.verify(0, postRequestedFor(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido")));
    }

    @Test
    void unPedidoSinItemsFallaAntesDeTocarLaRed() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cliente\":\"8380\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PEDIDO_INVALIDO"));

        gescom.verify(0, postRequestedFor(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido")));
    }

    @Test
    void unaCantidadNoPositivaFallaAntesDeTocarLaRed() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cliente\":\"8380\",\"items\":[{\"codigo\":\"X\",\"cantidad\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PEDIDO_INVALIDO"));

        gescom.verify(0, postRequestedFor(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido")));
    }

    /**
     * Si el ERP devuelve una linea que no cierra, no se devuelve el numero: en un checkout eso
     * termina en una factura mal.
     */
    @Test
    void unaLineaIncoherenteNoSeDevuelve() throws Exception {
        gescom.stubFor(post(urlPathEqualTo("/data/cmd/ventas/api/v1/eval-pedido"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"indiceVenta":1,"items":[{"itemCodigo":"5000014792",
                                  "precioNetoTotal":58424.22,"precioNetoTotalConDesc":58424.22,
                                  "descuentoTotal":0.1,"cantidad":6.0,"detalleDescuento":[]}]}]
                                """)));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.codigo").value("RESPUESTA_INCOHERENTE"));
    }
}
