package com.axum.bonificaciones.app.config;

import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras.Distribuidora;
import java.util.List;

/**
 * De donde salen las distribuidoras y sus credenciales.
 *
 * Hay dos implementaciones y la diferencia no es un detalle de infraestructura:
 *
 * - {@link RepositorioDeDistribuidoras} (la base) es la de produccion. Es la unica que escala a
 *   ~1000 distribuidoras y la unica que permite un alta sin reiniciar el servicio.
 * - {@link ConfiguracionDeDistribuidoras} (variables de entorno) queda para los tests y para
 *   levantar la app sin base. Era el andamio de las fases 0 a 2.
 *
 * Cual esta activa lo decide {@code bonificaciones.distribuidoras-en-base} y lo informa /health:
 * un deploy que quedo sin base tiene que verse ahi, no cuando alguien da de alta una
 * distribuidora y al reiniciar no esta.
 */
public interface Distribuidoras {

    Distribuidora requerir(String tenant);

    List<String> codigosActivos();

    /** Para /health: de donde esta leyendo. */
    String origen();
}
