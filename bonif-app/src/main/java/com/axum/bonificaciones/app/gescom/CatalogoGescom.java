package com.axum.bonificaciones.app.gescom;

import com.axum.bonificaciones.app.config.Distribuidoras;
import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.axum.bonificaciones.core.model.Criterio;
import com.axum.bonificaciones.core.puerto.CatalogoDeCriterios;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * El catalogo de criterios de GESCOM (get-promociones, que es donde viven las bonificaciones:
 * no hay endpoint separado).
 *
 * Cacheado por tenant con dos tiempos:
 *
 * - {@code refreshMinutos}: cuanto se sirve el dato sin consultar a GESCOM. Default 60 min.
 *   Los criterios son la estructura comercial de la distribuidora y cambian cuando alguien los
 *   edita en GESCOM, no con cada venta. El consumidor ve {@code actualizadoEn} para saber
 *   que tan fresco es lo que le llego.
 *
 * - {@code evictHoras}: cuanto se guarda en memoria sin que nadie lo pida. Default 4 h.
 *   Con ~1000 distribuidoras posibles, solo las que se consultan ocupan memoria. Una que nadie
 *   pide en 4 horas se descarta; la proxima consulta la trae de GESCOM de vuelta.
 *
 * Si GESCOM falla al refrescar y hay datos en cache (aunque vencidos), se sirven los viejos
 * en vez de voltear la consulta. Solo se falla si no hay nada cacheado.
 */
@Service
public class CatalogoGescom implements CatalogoDeCriterios {

    private static final Logger log = LoggerFactory.getLogger(CatalogoGescom.class);

    private static final ParameterizedTypeReference<List<DtosGescom.Promocion>> PROMOCIONES =
            new ParameterizedTypeReference<>() {};

    private final ClienteGescom cliente;
    private final MapeadorGescom mapeador;
    private final Distribuidoras distribuidoras;
    private final Cache<String, EntradaDeCatalogo> cache;
    private final Duration ttlRefresh;

    /**
     * @param actualizadoEn cuando se trajo de GESCOM, no cuando se sirvio. Si es viejo y GESCOM
     *                      esta caido, es porque estamos sirviendo datos en cache como fallback
     */
    public record EntradaDeCatalogo(List<Criterio> criterios, Instant actualizadoEn) {
        boolean debeRefrescar(Duration ttl) {
            return Duration.between(actualizadoEn, Instant.now()).compareTo(ttl) > 0;
        }
    }

    CatalogoGescom(ClienteGescom cliente, MapeadorGescom mapeador,
                   Distribuidoras distribuidoras,
                   @Value("${bonificaciones.gescom.cache-criterios-minutos:60}") long refreshMinutos,
                   @Value("${bonificaciones.gescom.cache-criterios-evict-horas:4}") long evictHoras) {
        this.cliente = cliente;
        this.mapeador = mapeador;
        this.distribuidoras = distribuidoras;
        this.ttlRefresh = Duration.ofMinutes(refreshMinutos);
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(evictHoras))
                .build();
    }

    @Override
    public List<Criterio> criterios(String tenant) {
        var entrada = cache.getIfPresent(tenant);

        if (entrada != null && !entrada.debeRefrescar(ttlRefresh)) {
            return entrada.criterios();
        }

        try {
            var nueva = new EntradaDeCatalogo(traer(tenant), Instant.now());
            cache.put(tenant, nueva);
            log.info("Catalogo de {} refrescado: {} criterios", tenant, nueva.criterios().size());
            return nueva.criterios();
        } catch (RuntimeException e) {
            if (entrada != null) {
                log.warn("No se pudo refrescar el catalogo de {} ({}). Sirviendo datos de {}.",
                        tenant, e.getMessage(), entrada.actualizadoEn());
                return entrada.criterios();
            }
            throw e;
        }
    }

    /** Cuando se trajeron los criterios de GESCOM por ultima vez, o null si no estan en cache. */
    public Instant actualizadoEn(String tenant) {
        var entrada = cache.getIfPresent(tenant);
        return entrada != null ? entrada.actualizadoEn() : null;
    }

    /** Descarta el catalogo cacheado de un tenant y fuerza que se vuelva a traer. */
    public void olvidar(String tenant) {
        cache.invalidate(tenant);
    }

    private List<Criterio> traer(String tenant) {
        var gescom = distribuidoras.requerir(tenant).gescom();
        if (gescom == null) {
            throw new ErrorDeGateway(CodigoDeError.FUENTE_NO_CONFIGURADA,
                    "La distribuidora " + tenant + " no tiene configurado GESCOM");
        }
        var promociones = cliente.get(tenant, gescom, "ventas", "get-promociones", PROMOCIONES);
        if (promociones == null) return List.of();
        return promociones.stream().map(p -> mapeador.aCriterio(tenant, p)).toList();
    }
}
