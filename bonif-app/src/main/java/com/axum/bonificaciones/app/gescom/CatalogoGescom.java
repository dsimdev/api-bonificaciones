package com.axum.bonificaciones.app.gescom;

import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras;
import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.axum.bonificaciones.core.model.Criterio;
import com.axum.bonificaciones.core.puerto.CatalogoDeCriterios;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * El catalogo de criterios de GESCOM (get-promociones, que es donde viven las bonificaciones:
 * no hay endpoint separado).
 *
 * Cacheado unos minutos por tenant: cambian cuando alguien los edita en GESCOM, y se consultan
 * una vez por valorizacion para poder explicar el descuento.
 */
@Service
public class CatalogoGescom implements CatalogoDeCriterios {

    private static final ParameterizedTypeReference<List<DtosGescom.Promocion>> PROMOCIONES =
            new ParameterizedTypeReference<>() {};

    private final ClienteGescom cliente;
    private final MapeadorGescom mapeador;
    private final ConfiguracionDeDistribuidoras configuracion;
    private final Cache<String, List<Criterio>> cache;

    CatalogoGescom(ClienteGescom cliente, MapeadorGescom mapeador,
                   ConfiguracionDeDistribuidoras configuracion) {
        this.cliente = cliente;
        this.mapeador = mapeador;
        this.configuracion = configuracion;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build();
    }

    @Override
    public List<Criterio> criterios(String tenant) {
        return cache.get(tenant, this::traer);
    }

    /** Descarta el catalogo cacheado de un tenant y fuerza que se vuelva a traer. */
    public void olvidar(String tenant) {
        cache.invalidate(tenant);
    }

    private List<Criterio> traer(String tenant) {
        var gescom = configuracion.requerir(tenant).gescom();
        if (gescom == null) {
            throw new ErrorDeGateway(CodigoDeError.FUENTE_NO_CONFIGURADA,
                    "La distribuidora " + tenant + " no tiene configurado GESCOM");
        }
        var promociones = cliente.get(tenant, gescom, "ventas", "get-promociones", PROMOCIONES);
        if (promociones == null) return List.of();
        return promociones.stream().map(p -> mapeador.aCriterio(tenant, p)).toList();
    }
}
