package com.axum.bonificaciones.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "bonificaciones.version=9.9.9",
        "bonificaciones.distribuidoras.dyssa.gescom.host=https://dyssa.gescom.online",
        "bonificaciones.distribuidoras.dyssa.gescom.realm=gcw-dyssa",
        "bonificaciones.distribuidoras.dyssa.gescom.usuario=u",
        "bonificaciones.distribuidoras.dyssa.gescom.clave=p",
        "bonificaciones.distribuidoras.otra.axum.tenant=otra",
        "bonificaciones.distribuidoras.otra.axum.api-key=k"
})
class SaludControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void healthInformaVersionYLasFuentesConfiguradasDeCadaDistribuidora() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ok"))
                .andExpect(jsonPath("$.version").value("9.9.9"))
                .andExpect(jsonPath("$.distribuidoras.dyssa[0]").value("GESCOM"))
                .andExpect(jsonPath("$.distribuidoras.otra[0]").value("AXUM"));
    }

    /**
     * Una distribuidora sin la seccion de una fuente no la lista: es el smoke test de un deploy
     * al que le falta una variable de entorno.
     */
    @Test
    void unaDistribuidoraSoloListaLasFuentesQueTieneConfiguradas() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distribuidoras.dyssa.length()").value(1))
                .andExpect(jsonPath("$.distribuidoras.otra.length()").value(1));
    }
}
