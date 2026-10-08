package com.axum.bonificaciones.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
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
 * Que pasa cuando GESCOM falla: el reintento y el cortacircuito.
 *
 * Contra WireMock y entrando por el endpoint publico, no llamando a Resiliencia a mano: lo que
 * importa es si una tienda que pide una valorizacion sobrevive un fallo de una vez, y eso depende
 * del ensamblado completo (token, clasificacion del error, reintento).
 *
 * El umbral se deja en el default (4 INTENTOS) a proposito: lo que cuenta son intentos y no
 * pedidos, asi que una valorizacion fallida gasta dos. Bajarlo para el test habria escondido
 * justamente esa relacion, que es la que se calcula mal.
 */
@SpringBootTest(properties = {
        // Sin espera: el test no tiene por que tardar 200ms por reintento.
        "bonificaciones.gescom.espera-reintento-ms=0"
})
@AutoConfigureMockMvc
class ResilienciaTest {

    private static final WireMockServer gescom =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        gescom.start();
    }

    private static final String EVAL = "/data/cmd/ventas/api/v1/eval-pedido";
    private static final String PROMOS = "/data/cmd/ventas/api/v1/get-promociones";

    private static final String PEDIDO = """
            {"cliente":"8380","listaPrecio":"2","items":[{"codigo":"5000014792","cantidad":6}]}
            """;

    private static final String UNA_LINEA = """
            [{"indiceVenta":1,"items":[{"itemCodigo":"5000014792","cantidad":6.0,
              "precioNetoTotal":100.00,"precioNetoTotalConDesc":90.00,"descuentoTotal":0.1,
              "detalleDescuento":[]}]}]
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    com.axum.bonificaciones.app.gescom.CatalogoGescom catalogo;

    @Autowired
    com.axum.bonificaciones.app.gescom.ServicioDeToken tokens;

    @Autowired
    com.axum.bonificaciones.app.gescom.Resiliencia resiliencia;

    @Autowired
    com.axum.bonificaciones.app.soporte.RegistroDeLlamadas registro;

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
        catalogo.olvidar("dyssa");
        tokens.olvidar("dyssa");
        // Los fallos acumulados viven en el bean, que es el mismo para toda la clase: sin esto un
        // test arranca con el circuito ya abierto por el anterior.
        resiliencia.olvidar("dyssa");
        registro.olvidarTodo();
        gescom.resetAll();
        gescom.stubFor(post(urlPathEqualTo("/realms/gcw-dyssa/protocol/openid-connect/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok-dyssa\",\"expires_in\":300}")));
        // El catalogo solo enriquece el "por que": que falle no tiene que voltear la valorizacion.
        gescom.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(PROMOS))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("[]")));
    }

    /**
     * EL TEST QUE JUSTIFICA EL REINTENTO.
     *
     * Un 503 de una vez no puede costar una venta. Reintentar es seguro porque eval-pedido es
     * dry-run: no hay nada que se pueda duplicar.
     */
    @Test
    void unFalloDeUnaVezSeReintentaYLaVentaSale() throws Exception {
        gescom.stubFor(post(urlPathEqualTo(EVAL)).inScenario("falla-una-vez")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("ya-fallo"));
        gescom.stubFor(post(urlPathEqualTo(EVAL)).inScenario("falla-una-vez")
                .whenScenarioStateIs("ya-fallo")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(UNA_LINEA)));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineas[0].descuento").value(10.0));

        gescom.verify(2, postRequestedFor(urlPathEqualTo(EVAL)));
    }

    /** Se reintenta UNA vez, no hasta que ande: hay alguien esperando en un checkout. */
    @Test
    void seReintentaUnaSolaVez() throws Exception {
        gescom.stubFor(post(urlPathEqualTo(EVAL))
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("FUENTE_NO_DISPONIBLE"));

        gescom.verify(2, postRequestedFor(urlPathEqualTo(EVAL)));
    }

    /**
     * Un pedido que el ERP rechaza NO se reintenta: va a fallar igual, y gastar otra llamada solo
     * suma latencia al checkout. Ademas no cuenta para el cortacircuito -- si contara, una tienda
     * con un bug de integracion dejaria a su distribuidora marcada como caida para todas las
     * demas tiendas de esa distribuidora.
     */
    @Test
    void unPedidoRechazadoNoSeReintenta() throws Exception {
        gescom.stubFor(post(urlPathEqualTo(EVAL))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"errorCode\":\"0\","
                                + "\"message\":\"El campo CodigoCliente tiene un codigo invalido\"}")));

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_INEXISTENTE"));

        gescom.verify(1, postRequestedFor(urlPathEqualTo(EVAL)));
    }

    /**
     * EL TEST QUE JUSTIFICA EL CORTACIRCUITO.
     *
     * Con una distribuidora caida, cada checkout esperaria el timeout completo (15s por intento,
     * 30 con el reintento) para devolver el mismo error. Despues de unos fallos seguidos se falla
     * rapido y se deja de castigar a la fuente y al que espera.
     *
     * Se verifica contando las llamadas: la tercera valorizacion NO tiene que llegar a GESCOM.
     */
    @Test
    void conLaFuenteCaidaSeDejaDeInsistir() throws Exception {
        gescom.stubFor(post(urlPathEqualTo(EVAL))
                .willReturn(aResponse().withStatus(503)));

        // Dos valorizaciones = 4 intentos (2 cada una), que con fallos-para-abrir=2 abre el
        // circuito.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                            .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                    .andExpect(status().isServiceUnavailable());
        }
        int llamadasHastaAhora = gescom.countRequestsMatching(
                postRequestedFor(urlPathEqualTo(EVAL)).build()).getCount();

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("FUENTE_NO_DISPONIBLE"))
                // El mensaje dice que se dejo de insistir: el consumidor ve el mismo codigo pero
                // soporte puede distinguir "timeout" de "circuito abierto".
                .andExpect(jsonPath("$.mensaje")
                        .value(org.hamcrest.Matchers.containsString("dejó de insistir")));

        gescom.verify(llamadasHastaAhora, postRequestedFor(urlPathEqualTo(EVAL)));
    }

    /**
     * Una respuesta buena REINICIA el contador: lo que abre el circuito son fallos SEGUIDOS, no
     * fallos acumulados para siempre.
     *
     * Por eso el test falla, anda, falla y vuelve a andar: son 2 + 2 = 4 intentos fallidos, justo
     * el umbral. Sin el reinicio del contador, ese ultimo pedido daria 503 con el circuito
     * abierto. Que de 200 es la prueba de que el exito del medio lo limpio.
     */
    @Test
    void unaRespuestaBuenaReiniciaElContador() throws Exception {
        fallar();
        andar();
        fallar();
        andar();
    }

    private void fallar() throws Exception {
        gescom.resetAll();
        stubsDeAuthYCatalogo();
        gescom.stubFor(post(urlPathEqualTo(EVAL)).willReturn(aResponse().withStatus(503)));
        tokens.olvidar("dyssa");
        catalogo.olvidar("dyssa");

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isServiceUnavailable());
    }

    private void andar() throws Exception {
        gescom.resetAll();
        stubsDeAuthYCatalogo();
        gescom.stubFor(post(urlPathEqualTo(EVAL))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(UNA_LINEA)));
        tokens.olvidar("dyssa");
        catalogo.olvidar("dyssa");

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType(MediaType.APPLICATION_JSON).content(PEDIDO))
                .andExpect(status().isOk());
    }

    /**
     * Lo que hace falta para contestar "la tienda dice que no funciona": cuantas llamadas hubo,
     * cuantas fallaron y CON QUE CODIGO DE DOMINIO -- no con un 502 pelado, que no dice nada.
     *
     * El codigo de dominio solo lo conoce el manejador de errores, asi que este test tambien fija
     * que el atributo que lo comunica al interceptor siga conectado: si alguien lo desconecta, el
     * panel empieza a mostrar HTTP_502 en vez de FUENTE_NO_DISPONIBLE y nadie se entera.
     */
    @Test
    void laActividadQuedaRegistradaConElCodigoDeDominio() throws Exception {
        andar();
        fallar();

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/v1/metricas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenant").value("dyssa"))
                .andExpect(jsonPath("$[0].total").value(2))
                .andExpect(jsonPath("$[0].fallidas").value(1))
                .andExpect(jsonPath("$[0].porCodigo.FUENTE_NO_DISPONIBLE").value(1));
    }

    /** Un 401 tambien se registra: es justo el que hace que nadie entienda por que "no funciona". */
    @Test
    void unRechazoDeAutenticacionTambienSeRegistra() throws Exception {
        // Sin base no hay interceptor de api-key, asi que el 401 no se puede provocar aca. Lo que
        // se prueba es el otro caso sin codigo de dominio: una ruta que no existe.
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/noexiste"))
                .andExpect(status().isNotFound());

        mockMvc.perform(MockMvcRequestBuilders.get("/admin/v1/metricas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fallidas").value(1))
                .andExpect(jsonPath("$[0].porCodigo.HTTP_404").value(1));
    }

    private void stubsDeAuthYCatalogo() {
        gescom.stubFor(post(urlPathEqualTo("/realms/gcw-dyssa/protocol/openid-connect/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok-dyssa\",\"expires_in\":300}")));
        gescom.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(PROMOS))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("[]")));
    }
}
