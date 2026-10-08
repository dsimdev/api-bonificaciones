package com.axum.bonificaciones.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.bonificaciones.app.admin.ServicioDeAltas;
import com.axum.bonificaciones.app.config.RepositorioDeDistribuidoras;
import com.axum.bonificaciones.app.seguridad.CifradoDeSecretos;
import com.axum.bonificaciones.app.seguridad.ContextoDeLlamada;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Recargar las credenciales despues de que cambio la CIFRADO_KEY.
 *
 * Es el procedimiento que la guia de deploy indica cuando la clave se pierde, y no andaba:
 * actualizar las credenciales descifraba primero la vieja, que con la clave nueva no se puede, asi
 * que ninguna distribuidora se podia recargar. Encontrado el 2026-10-08 al perder la clave local.
 *
 * Etiquetado "db". Usa codigos "zzz-test-" y borra solo esos.
 */
@Tag("db")
@SpringBootTest(properties = {
        "bonificaciones.distribuidoras-en-base=true",
        "bonificaciones.cifrado-key=" + CredencialesConCifradoNuevoIT.CLAVE_NUEVA,
        "spring.autoconfigure.exclude="
})
class CredencialesConCifradoNuevoIT {

    static final String CLAVE_NUEVA = "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String CLAVE_PERDIDA = "2222222222222222222222222222222222222222222222222222222222222222";
    private static final String CODIGO = "zzz-test-rotada";

    private static final WireMockServer gescom =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        gescom.start();
    }

    @AfterAll
    static void bajar() {
        gescom.stop();
    }

    @DynamicPropertySource
    static void apuntarAlStub(DynamicPropertyRegistry registry) {
        registry.add("bonificaciones.gescom.url-auth", gescom::baseUrl);
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RepositorioDeDistribuidoras distribuidoras;

    @Autowired
    ServicioDeAltas altas;

    @BeforeEach
    void datos() throws Exception {
        limpiar();
        jdbc.sql("""
                        INSERT INTO distribuidora (codigo, host, realm, gescom_usuario, gescom_clave)
                        VALUES (:codigo, :host, 'gcw-test', 'viejo', :clave)
                        """)
                .param("codigo", CODIGO)
                .param("host", gescom.baseUrl())
                // Cifrada con OTRA clave: es lo que queda en la base cuando la CIFRADO_KEY se pierde.
                .param("clave", new CifradoDeSecretos(CLAVE_PERDIDA).cifrar("clave-vieja"))
                .update();

        String catalogo;
        try (var in = getClass().getResourceAsStream("/fixtures/get-promociones-dyssa.json")) {
            catalogo = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        gescom.resetAll();
        gescom.stubFor(post(urlPathEqualTo("/realms/gcw-test/protocol/openid-connect/token"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"tok\",\"expires_in\":300}")));
        gescom.stubFor(get(urlPathEqualTo("/data/cmd/ventas/api/v1/get-promociones"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(catalogo)));
        ContextoDeLlamada.establecer("test");
    }

    @AfterEach
    void limpiarAlFinal() {
        ContextoDeLlamada.limpiar();
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

    @Test
    void conLaClaveNuevaLaCredencialViejaNoSePuedeLeer() {
        assertThrows(IllegalStateException.class, () -> distribuidoras.requerir(CODIGO));
    }

    @Test
    void igualSePuedeRecargarYQuedaCifradaConLaNueva() {
        int criterios = altas.actualizarCredenciales(CODIGO, "nuevo", "clave-nueva");

        assertTrue(criterios > 0, "tiene que haber probado contra GESCOM antes de guardar");
        var gescomGuardado = distribuidoras.requerir(CODIGO).gescom();
        assertEquals("nuevo", gescomGuardado.usuario());
        assertEquals("clave-nueva", gescomGuardado.clave());
    }
}
