package com.axum.bonificaciones.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.bonificaciones.app.seguridad.Credencial;
import com.axum.bonificaciones.app.seguridad.RepositorioDeCredenciales;
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
 * <b>OJO: VACIA las tablas `credencial` y `distribuidora` de la base local</b> antes de cada test.
 * Si tenias distribuidoras cargadas a mano para probar el panel, despues de correr el build con
 * -PincludeDbTests ya no estan -- y peor, quedan las de este test, que parecen validas (activa=1)
 * pero apuntan a un puerto cerrado. Volve a darlas de alta. La tabla `usuario` no se toca.
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
    com.axum.bonificaciones.app.seguridad.CifradoDeSecretos cifrado;

    private String claveDeDyssa;
    private String claveDeSenderolaser;
    private String claveAdminDeDyssa;

    @BeforeEach
    void datos() {
        jdbc.sql("DELETE FROM credencial").update();
        jdbc.sql("DELETE FROM distribuidora").update();

        claveDeDyssa = alta("dyssa", Credencial.Alcance.VALORIZACION);
        claveAdminDeDyssa = credenciales.generar(idDe("dyssa"), Credencial.Alcance.ADMIN,
                "back-office", "test");
        claveDeSenderolaser = alta("senderolaser", Credencial.Alcance.VALORIZACION);
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
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .contentType("application/json").content(PEDIDO))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.codigo").value("NO_AUTORIZADO"));
    }

    @Test
    void unaClaveInventadaNoSirve() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
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
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/dyssa/valorizaciones")
                        .header("x-api-key", claveDeSenderolaser)
                        .contentType("application/json").content(PEDIDO))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(MockMvcResultMatchers.jsonPath("$.mensaje")
                        .value("Esa clave no es de la distribuidora dyssa."));
    }

    /**
     * El catalogo es la estructura comercial completa de la distribuidora. La clave del checkout
     * vive en un navegador y se lee del DevTools: no puede abrir eso.
     */
    @Test
    void laClaveDelCheckoutNoPuedeLeerElCatalogo() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/criterios")
                        .header("x-api-key", claveDeDyssa))
                .andExpect(MockMvcResultMatchers.status().isForbidden())
                .andExpect(MockMvcResultMatchers.jsonPath("$.codigo").value("ALCANCE_INSUFICIENTE"));
    }

    @Test
    void laClaveAdminSiPuedeLeerElCatalogo() throws Exception {
        // Pasa la puerta y muere en el conector contra un puerto cerrado: 503, no 403. Lo que se
        // prueba es que el alcance ADMIN NO la rechaza.
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dyssa/criterios")
                        .header("x-api-key", claveAdminDeDyssa))
                .andExpect(MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(MockMvcResultMatchers.jsonPath("$.codigo").value("FUENTE_NO_DISPONIBLE"));
    }

    @Test
    void generarUnaClaveNuevaRevocaLaAnterior() throws Exception {
        var vieja = claveDeDyssa;
        var nueva = credenciales.generar(idDe("dyssa"), Credencial.Alcance.VALORIZACION,
                "checkout", "test");

        assertNotEquals(vieja, nueva);
        assertTrue(credenciales.buscarPorClave(vieja).isEmpty(), "la vieja tiene que quedar revocada");
        assertEquals("dyssa", credenciales.buscarPorClave(nueva).orElseThrow().tenant());
    }

    /** /health no pide clave: lo mira el monitoreo y no expone nada de ninguna distribuidora. */
    @Test
    void healthNoPideClave() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/health"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.origen").value("BASE"));
    }
}
