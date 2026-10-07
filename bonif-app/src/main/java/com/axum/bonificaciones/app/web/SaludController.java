package com.axum.bonificaciones.app.web;

import com.axum.bonificaciones.app.config.Distribuidoras;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SaludController {

    private final String version;
    private final Distribuidoras distribuidoras;

    SaludController(@Value("${bonificaciones.version:dev}") String version,
                    Distribuidoras distribuidoras) {
        this.version = version;
        this.distribuidoras = distribuidoras;
    }

    /**
     * Informa la version que esta corriendo de verdad, de donde salen las distribuidoras y
     * cuantas hay.
     *
     * Es el smoke test barato de un deploy: una base que no conecta, o un deploy que quedo
     * leyendo de variables de entorno cuando deberia leer de la base, se ve aca -- no cuando
     * alguien da de alta una distribuidora y al reiniciar no esta.
     */
    @GetMapping("/health")
    public Salud salud() {
        List<String> codigos;
        String estado = "ok";
        try {
            codigos = distribuidoras.codigosActivos();
        } catch (RuntimeException e) {
            // Si la base no responde, /health tiene que decirlo en vez de tirar 500: es
            // justamente la pregunta que se le hace a /health.
            codigos = List.of();
            estado = "sin-acceso-a-distribuidoras";
        }
        return new Salud(estado, version, distribuidoras.origen(), codigos.size(), codigos);
    }

    /**
     * @param origen BASE o CONFIGURACION. En produccion tiene que decir BASE
     * @param distribuidoras se listan completas; con ~1000 esto es largo, pero sirve para
     *                       diagnosticar y no es un endpoint de trafico
     */
    public record Salud(String estado, String version, String origen, int total,
                        List<String> distribuidoras) {}
}
