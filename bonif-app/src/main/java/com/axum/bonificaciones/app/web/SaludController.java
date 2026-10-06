package com.axum.bonificaciones.app.web;

import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SaludController {

    private final String version;
    private final ConfiguracionDeDistribuidoras configuracion;

    SaludController(@Value("${bonificaciones.version:dev}") String version,
                    ConfiguracionDeDistribuidoras configuracion) {
        this.version = version;
        this.configuracion = configuracion;
    }

    /**
     * Informa la version que esta corriendo de verdad y que distribuidoras quedaron configuradas.
     * Lo segundo es el smoke test barato de un deploy: una variable de entorno que falta se ve
     * aca, no recien cuando alguien consulta criterios.
     */
    @GetMapping("/health")
    public Salud salud() {
        return new Salud("ok", version, configuracion.codigos().stream().sorted().toList());
    }

    public record Salud(String estado, String version, List<String> distribuidoras) {}
}
