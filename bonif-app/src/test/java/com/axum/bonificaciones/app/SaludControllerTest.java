package com.axum.bonificaciones.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "bonificaciones.version=9.9.9",
        "bonificaciones.distribuidoras.dyssa.erp=GESCOM",
        "bonificaciones.distribuidoras.dyssa.host=https://dyssa.gescom.online",
        "bonificaciones.distribuidoras.dyssa.realm=gcw-dyssa",
        "bonificaciones.distribuidoras.dyssa.usuario=u",
        "bonificaciones.distribuidoras.dyssa.clave=p"
})
class SaludControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void healthInformaVersionYDistribuidorasConfiguradas() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ok"))
                .andExpect(jsonPath("$.version").value("9.9.9"))
                .andExpect(jsonPath("$.distribuidoras[0]").value("dyssa"));
    }
}
