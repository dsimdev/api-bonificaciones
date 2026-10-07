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
        "bonificaciones.distribuidoras.otra.gescom.host=https://otra.gescom.online",
        "bonificaciones.distribuidoras.otra.gescom.realm=gcw-otra",
        "bonificaciones.distribuidoras.otra.gescom.usuario=u",
        "bonificaciones.distribuidoras.otra.gescom.clave=p"
})
class SaludControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void healthInformaLaVersionQueRealmenteCorre() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ok"))
                .andExpect(jsonPath("$.version").value("9.9.9"));
    }

    /**
     * De donde salen las distribuidoras es parte de /health a proposito: un deploy que quedo
     * leyendo variables de entorno cuando deberia leer de la base se tiene que ver aca, no cuando
     * alguien da de alta una distribuidora y al reiniciar no esta.
     *
     * En este test dice CONFIGURACION porque los tests corren sin base; en produccion tiene que
     * decir BASE.
     */
    @Test
    void healthDiceDeDondeSalenLasDistribuidoras() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origen").value("CONFIGURACION"));
    }

    @Test
    void healthListaLasDistribuidorasConfiguradas() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.distribuidoras[0]").value("dyssa"))
                .andExpect(jsonPath("$.distribuidoras[1]").value("otra"));
    }
}
