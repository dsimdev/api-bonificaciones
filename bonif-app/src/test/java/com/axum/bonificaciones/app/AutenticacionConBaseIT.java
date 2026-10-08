package com.axum.bonificaciones.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.bonificaciones.app.seguridad.Credencial;
import com.axum.bonificaciones.app.seguridad.RepositorioDeCredenciales;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * La autenticacion de la API publica, contra la base de verdad.
 *
 * Etiquetado "db": necesita SQL Server local con la base creada (scripts/crear-base.sql). Se
 * excluye del build por defecto; correr con -PincludeDbTests.
 *
 * No pega contra GESCOM: las distribuidoras se insertan a mano. Lo que se prueba aca es la puerta,
 * no el conector.
 *
 * <b>No toca los datos reales de la base local.</b> Usa codigos con el prefijo "zzz-test-" y borra
 * SOLO esos. La primera version borraba las dos tablas enteras y usaba los codigos de verdad
 * (dyssa, senderolaser): correr el build te dejaba sin las distribuidoras que tenias cargadas para
 * probar el panel y, peor, con las del test en su lugar -- activas, asi que parecian buenas, pero
 * apuntando a un puerto cerrado. Paso dos veces en una tarde.
 */
@Tag("db")
@SpringBootTest(properties = {
        "bonificaciones.distribuidoras-en-base=true",
        "bonificaciones.cifrado-key=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        // Keycloak a un puerto cerrado: este test no tiene que salir a internet. Lo que prueba es
        // la puerta, y lo que pasa la puerta tiene que morir en un error MANEJADO.
        "bonificaciones.gescom.url-auth=http://localhost:1",
        "spring.autoconfigure.exclude="
})
@AutoConfigureMockMvc
class AutenticacionConBaseIT {

    private static final String PEDIDO = """
            {"cliente":"8380","listaPrecio":"2",
             "items":[{"codigo":"5000014792","cantidad":6}]}
            """;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RepositorioDeCredenciales credenciales;

    @Autowired
    com.axum.bonificaciones.app.soporte.RegistroDeLlamadas registro;

    @Autowired
    com.axum.bonificaciones.app.seguridad.CifradoDeSecretos cifrado;

    /**
     * Prefijo que no puede chocar con una distribuidora de verdad: el codigo es lo que va en la
     * URL, asi que nadie va a tener una llamada "zzz-test-...". Es lo que permite limpiar solo lo
     * de este test en vez de vaciar las tablas.
     */
    private static final String UNA = "zzz-test-una";
    private static final String OTRA = "zzz-test-otra";

    private String claveDeUna;
    private String claveDeOtra;
    private String claveAdminDeUna;

    @BeforeEach
    void datos() {
        limpiar();
        claveDeUna = alta(UNA, Credencial.Alcance.VALORIZACION);
        claveAdminDeUna = credenciales.generar(idDe(UNA), Credencial.Alcance.ADMIN,
                "back-office", "test");
        claveDeOtra = alta(OTRA, Credencial.Alcance.VALORIZACION);
    }

    @AfterEach
    void limpiarAlFinal() {
        // Tambien al final, no solo al principio: si no, las filas del ultimo test quedan en la
        // base pareciendo distribuidoras validas.
        limpiar();
    }

    private void limpiar() {
        jdbc.sql("""
                        DELETE FROM credencial
                        WHERE distribuidora_id IN
                              (SELECT id FROM distribuidora WHERE codigo LIKE 'zzz-test-%')
                        """).update();
        jdbc.sql("DELETE FROM distribuidora WHERE codigo LIKE 'zzz-test-%'").update();
    }

    /**
     * El host apunta a un puerto cerrado a proposito: estos tests prueban LA PUERTA, no el
     * conector. Si una request pasa la autenticacion, tiene que morir en un 503 manejado y no
     * salir a internet ni explotar sin mapear.
     */
    private String alta(String codigo, Credencial.Alcance alcance) {
        jdbc.sql("""
                        INSERT INTO distribuidora (codigo, host, realm, gescom_usuario, gescom_clave)
                        VALUES (:codigo, :host, 'gcw-test', 'u', :clave)
                        """)
                .param("codigo", codigo)
                // Por parametro y no inline: el ":1" del puerto lo toma como parametro nombrado.
                .param("host", "http://localhost:1")
                .param("clave", cifrado.cifrar("no-se-usa"))
                .update();
        return credenciales.generar(idDe(codigo), alcance, "checkout", "test");
    }

    private long idDe(String codigo) {
        return jdbc.sql("SELECT id FROM distribuidora WHERE codigo = :c")
                .param("c", codigo).query(Long.class).single();
    }

    @Test
    void sinClaveNoSePuedeValorizar() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/" + UNA + "/valorizaciones")
                        .contentType("application/json").content(PEDIDO))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.codigo").value("NO_AUTORIZADO"));
    }

    @Test
    void unaClaveInventadaNoSirve() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/" + UNA + "/valorizaciones")
                        .header("x-api-key", "bon_cualquiera")
                        .contentType("application/json").content(PEDIDO))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    /**
     * EL TEST QUE JUSTIFICA LA AUTENTICACION POR TENANT.
     *
     * Sin el chequeo de "la credencial es del tenant de la ruta", cualquier tienda con una clave
     * valida podria pedir los precios y descuentos de OTRA distribuidora cambiando la URL. Es el
     * mismo agujero que el aislamiento de tokens cubre del lado de GESCOM, pero en la entrada.
     */
    @Test
    void laClaveDeUnaDistribuidoraNoSirveParaOtra() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/" + UNA + "/valorizaciones")
                        .header("x-api-key", claveDeOtra)
                        .contentType("application/json").content(PEDIDO))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.mensaje")
                        .value("Esa clave no es de la distribuidora " + UNA + "."));
    }

    /**
     * El catalogo es la estructura comercial completa de la distribuidora. La clave del checkout
     * vive en un navegador y se lee del DevTools: no puede abrir eso.
     */
    @Test
    void laClaveDelCheckoutNoPuedeLeerElCatalogo() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/" + UNA + "/criterios")
                        .header("x-api-key", claveDeUna))
                .andExpect(MockMvcResultMatchers.status().isForbidden())
                .andExpect(MockMvcResultMatchers.jsonPath("$.codigo").value("ALCANCE_INSUFICIENTE"));
    }

    @Test
    void laClaveAdminSiPuedeLeerElCatalogo() throws Exception {
        // Pasa la puerta y muere en el conector contra un puerto cerrado: 503, no 403. Lo que se
        // prueba es que el alcance ADMIN NO la rechaza.
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/" + UNA + "/criterios")
                        .header("x-api-key", claveAdminDeUna))
                .andExpect(MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(MockMvcResultMatchers.jsonPath("$.codigo").value("FUENTE_NO_DISPONIBLE"));
    }

    @Test
    void generarUnaClaveNuevaRevocaLaAnterior() throws Exception {
        var vieja = claveDeUna;
        var nueva = credenciales.generar(idDe(UNA), Credencial.Alcance.VALORIZACION,
                "checkout", "test");

        assertNotEquals(vieja, nueva);
        assertTrue(credenciales.buscarPorClave(vieja).isEmpty(), "la vieja tiene que quedar revocada");
        assertEquals(UNA, credenciales.buscarPorClave(nueva).orElseThrow().tenant());
    }

    /**
     * EL TEST QUE ATRAPA UN BUG DE ORDEN DE INTERCEPTORES.
     *
     * Un pedido rechazado por la autenticacion tiene que quedar REGISTRADO. Es el caso que mas
     * seguido explica un "la tienda dice que no funciona": la clave vencio o la cambiaron, y sin
     * esto en el panel no se ve ni un intento, asi que parece que la tienda nunca llamo.
     *
     * Spring solo llama al afterCompletion de los interceptores que ya habian pasado cuando otro
     * rechaza el pedido. Con el de registro anotado DESPUES del de api-key, estos 401 no se
     * contaban -- y este es el unico test que puede verlo, porque el interceptor de api-key solo
     * existe con la base enchufada.
     */
    @Test
    void unPedidoSinClaveQuedaRegistrado() throws Exception {
        registro.olvidarTodo();

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/" + UNA + "/valorizaciones")
                        .contentType("application/json").content(PEDIDO))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized());

        var actividad = registro.actividad();
        assertEquals(1, actividad.size(), "el 401 tiene que quedar registrado");
        assertEquals(1, actividad.get(0).fallidas());
        assertEquals(1L, actividad.get(0).porCodigo().get("NO_AUTORIZADO"));
    }

    /** /health no pide clave: lo mira el monitoreo y no expone nada de ninguna distribuidora. */
    @Test
    void healthNoPideClave() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/health"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.origen").value("BASE"));
    }
}
