package com.axum.bonificaciones.app.soporte;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La actividad de la API publica, por distribuidora.
 *
 * Va bajo /admin: cuantas llamadas hace cada tienda y como le salen es informacion de todas las
 * distribuidoras juntas, y eso no se le muestra a ninguna.
 *
 * Son contadores en memoria, desde que arranco el servicio. Para "que paso ayer a las tres" esta
 * el log, que WinSW rota por dia en el servidor -- ver RegistroDeLlamadas.
 */
@RestController
@RequestMapping("/admin/v1/metricas")
public class MetricasController {

    private final RegistroDeLlamadas registro;

    MetricasController(RegistroDeLlamadas registro) {
        this.registro = registro;
    }

    @Operation(summary = "Actividad de la API publica por distribuidora",
            description = "Contadores en memoria desde que arranco el servicio, ordenados por "
                    + "cantidad de fallas. Vacio significa que todavia nadie llamo.")
    @GetMapping
    public List<RegistroDeLlamadas.Actividad> actividad() {
        return registro.actividad();
    }
}
