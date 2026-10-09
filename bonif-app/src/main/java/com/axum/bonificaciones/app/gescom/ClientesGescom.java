package com.axum.bonificaciones.app.gescom;

import com.axum.bonificaciones.app.config.Distribuidoras;
import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Los clientes de GESCOM (get-clientes, servicio ventas), cacheados por tenant.
 *
 * Se usa para filtrar criterios por cliente: dado un codigo de cliente, saber sus tags y subramo
 * para decidir que criterios le aplican. No se expone en la API.
 */
@Service
public class ClientesGescom {

    private static final Logger log = LoggerFactory.getLogger(ClientesGescom.class);

    private static final ParameterizedTypeReference<List<DtosGescom.ClienteGescomDto>> CLIENTES =
            new ParameterizedTypeReference<>() {};

    private final ClienteGescom cliente;
    private final Distribuidoras distribuidoras;
    private final Cache<String, EntradaDeClientes> cache;
    private final Duration ttlRefresh;

    public record EntradaDeClientes(Map<String, DatosDeCliente> porCodigo, Instant actualizadoEn) {
        boolean debeRefrescar(Duration ttl) {
            return Duration.between(actualizadoEn, Instant.now()).compareTo(ttl) > 0;
        }
    }

    public record DatosDeCliente(String codigo, String subramo, Set<String> tags) {}

    ClientesGescom(ClienteGescom cliente, Distribuidoras distribuidoras,
                   @Value("${bonificaciones.gescom.cache-criterios-minutos:60}") long refreshMinutos,
                   @Value("${bonificaciones.gescom.cache-criterios-evict-horas:4}") long evictHoras) {
        this.cliente = cliente;
        this.distribuidoras = distribuidoras;
        this.ttlRefresh = Duration.ofMinutes(refreshMinutos);
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(evictHoras))
                .build();
    }

    /** Busca un cliente por codigo. Null si no existe. */
    public DatosDeCliente buscar(String tenant, String codigoCliente) {
        return clientes(tenant).get(codigoCliente);
    }

    public void olvidar(String tenant) {
        cache.invalidate(tenant);
    }

    private Map<String, DatosDeCliente> clientes(String tenant) {
        var entrada = cache.getIfPresent(tenant);

        if (entrada != null && !entrada.debeRefrescar(ttlRefresh)) {
            return entrada.porCodigo();
        }

        try {
            var nueva = new EntradaDeClientes(traer(tenant), Instant.now());
            cache.put(tenant, nueva);
            log.info("Clientes de {} refrescados: {} clientes", tenant, nueva.porCodigo().size());
            return nueva.porCodigo();
        } catch (RuntimeException e) {
            if (entrada != null) {
                log.warn("No se pudo refrescar los clientes de {} ({}). Sirviendo datos de {}.",
                        tenant, e.getMessage(), entrada.actualizadoEn());
                return entrada.porCodigo();
            }
            throw e;
        }
    }

    private Map<String, DatosDeCliente> traer(String tenant) {
        var gescom = distribuidoras.requerir(tenant).gescom();
        if (gescom == null) {
            throw new ErrorDeGateway(CodigoDeError.FUENTE_NO_CONFIGURADA,
                    "La distribuidora " + tenant + " no tiene configurado GESCOM");
        }
        var lista = cliente.get(tenant, gescom, "ventas", "get-clientes", CLIENTES);
        if (lista == null) return Map.of();
        return lista.stream().collect(Collectors.toMap(
                DtosGescom.ClienteGescomDto::codigo,
                c -> new DatosDeCliente(
                        c.codigo(),
                        c.codigoSubramo(),
                        c.tags() != null ? Set.copyOf(c.tags()) : Set.of()),
                (a, b) -> a));
    }
}
