package com.axum.bonificaciones.app.gescom;

import com.axum.bonificaciones.app.config.Distribuidoras;
import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
 * El catalogo de articulos de GESCOM (get-articulos, servicio inventario).
 *
 * Se usa para resolver "marca pepsico-11 = articulos X, Y, Z" al enriquecer los criterios.
 * Cacheado igual que los criterios: lazy por tenant, con fallback a datos vencidos si GESCOM
 * no responde.
 *
 * No se expone en la API: es un dato interno que solo usa el endpoint de criterios para
 * pre-resolver las condiciones de articulo.
 */
@Service
public class ArticulosGescom {

    private static final Logger log = LoggerFactory.getLogger(ArticulosGescom.class);

    private static final ParameterizedTypeReference<List<DtosGescom.Articulo>> ARTICULOS =
            new ParameterizedTypeReference<>() {};

    private final ClienteGescom cliente;
    private final Distribuidoras distribuidoras;
    private final Cache<String, EntradaDeArticulos> cache;
    private final Duration ttlRefresh;

    public record EntradaDeArticulos(IndiceDeArticulos indice, Instant actualizadoEn) {
        boolean debeRefrescar(Duration ttl) {
            return Duration.between(actualizadoEn, Instant.now()).compareTo(ttl) > 0;
        }
    }

    ArticulosGescom(ClienteGescom cliente, Distribuidoras distribuidoras,
                    @Value("${bonificaciones.gescom.cache-criterios-minutos:60}") long refreshMinutos,
                    @Value("${bonificaciones.gescom.cache-criterios-evict-horas:4}") long evictHoras) {
        this.cliente = cliente;
        this.distribuidoras = distribuidoras;
        this.ttlRefresh = Duration.ofMinutes(refreshMinutos);
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(evictHoras))
                .build();
    }

    public IndiceDeArticulos articulos(String tenant) {
        var entrada = cache.getIfPresent(tenant);

        if (entrada != null && !entrada.debeRefrescar(ttlRefresh)) {
            return entrada.indice();
        }

        try {
            var nueva = new EntradaDeArticulos(traer(tenant), Instant.now());
            cache.put(tenant, nueva);
            log.info("Articulos de {} refrescados: {} articulos", tenant, nueva.indice().total());
            return nueva.indice();
        } catch (RuntimeException e) {
            if (entrada != null) {
                log.warn("No se pudo refrescar los articulos de {} ({}). Sirviendo datos de {}.",
                        tenant, e.getMessage(), entrada.actualizadoEn());
                return entrada.indice();
            }
            throw e;
        }
    }

    public void olvidar(String tenant) {
        cache.invalidate(tenant);
    }

    private IndiceDeArticulos traer(String tenant) {
        var gescom = distribuidoras.requerir(tenant).gescom();
        if (gescom == null) {
            throw new ErrorDeGateway(CodigoDeError.FUENTE_NO_CONFIGURADA,
                    "La distribuidora " + tenant + " no tiene configurado GESCOM");
        }
        var arts = cliente.get(tenant, gescom, "inventario", "get-articulos", ARTICULOS);
        return IndiceDeArticulos.de(arts != null ? arts : List.of());
    }

    /**
     * Un indice invertido de articulos: dado un atributo y su valor, devuelve los codigos de
     * articulo que lo tienen. Armado una vez al cargar, se consulta O(1) por cada condicion.
     */
    public static final class IndiceDeArticulos {

        private final Map<String, Set<String>> porMarca;
        private final Map<String, Set<String>> porRubro;
        private final Map<String, Set<String>> porProveedor;
        private final Map<String, Set<String>> porLinea;
        private final Map<String, Set<String>> porFamilia;
        private final Map<String, Set<String>> porTag;
        private final Set<String> todos;
        private final int total;

        private IndiceDeArticulos(List<DtosGescom.Articulo> articulos) {
            this.total = articulos.size();
            this.todos = articulos.stream().map(DtosGescom.Articulo::codigo)
                    .collect(Collectors.toSet());

            this.porMarca = indexar(articulos, DtosGescom.Articulo::codigoMarca);
            this.porRubro = indexar(articulos, DtosGescom.Articulo::codigoRubro);
            this.porProveedor = indexar(articulos, DtosGescom.Articulo::codigoProveedor);
            this.porLinea = indexar(articulos, DtosGescom.Articulo::codigoLinea);
            this.porFamilia = indexar(articulos, DtosGescom.Articulo::codigoFamilia);

            var tagIndex = new HashMap<String, Set<String>>();
            for (var a : articulos) {
                if (a.tags() != null) {
                    for (var tag : a.tags()) {
                        tagIndex.computeIfAbsent(tag, k -> new java.util.HashSet<>()).add(a.codigo());
                    }
                }
            }
            this.porTag = tagIndex;
        }

        static IndiceDeArticulos de(List<DtosGescom.Articulo> articulos) {
            return new IndiceDeArticulos(articulos);
        }

        public int total() { return total; }

        public Set<String> todos() { return todos; }

        /**
         * Los codigos de articulo que matchean UNA condicion hoja de articulo.
         * Para condiciones de cliente (TAG_CLIENTE, CODIGO_CLIENTE, SUBRAMO_CLIENTE) devuelve
         * vacio: esas no filtran articulos.
         */
        public Set<String> articulosQueMatchean(com.axum.bonificaciones.core.model.Condicion c) {
            var valores = c.valores();
            var resultado = switch (c.tipo()) {
                case MARCA_ARTICULO -> buscarEnIndice(porMarca, valores);
                case RUBRO_ITEM -> buscarEnIndice(porRubro, valores);
                case PROVEEDOR_ARTICULO -> buscarEnIndice(porProveedor, valores);
                case LINEA_ARTICULO -> buscarEnIndice(porLinea, valores);
                case FAMILIA_ARTICULO -> buscarEnIndice(porFamilia, valores);
                case TAG_ITEM -> buscarEnIndice(porTag, valores);
                case CODIGO_ITEM -> Set.copyOf(valores);
                // CALIBRE_ARTICULO no esta en get-articulos de dyssa; si aparece, no filtra
                default -> Set.<String>of();
            };
            if (c.invertida()) {
                var complemento = new java.util.HashSet<>(todos);
                complemento.removeAll(resultado);
                return complemento;
            }
            return resultado;
        }

        /**
         * true si la condicion es de tipo articulo y se puede resolver contra este indice.
         */
        public boolean esCondicionDeArticulo(com.axum.bonificaciones.core.model.Condicion c) {
            return switch (c.tipo()) {
                case MARCA_ARTICULO, RUBRO_ITEM, PROVEEDOR_ARTICULO, LINEA_ARTICULO,
                     FAMILIA_ARTICULO, TAG_ITEM, CODIGO_ITEM, CALIBRE_ARTICULO -> true;
                default -> false;
            };
        }

        private static Set<String> buscarEnIndice(Map<String, Set<String>> indice,
                                                   List<String> valores) {
            if (valores.size() == 1) {
                var r = indice.get(valores.get(0));
                return r != null ? r : Set.of();
            }
            var resultado = new java.util.HashSet<String>();
            for (var v : valores) {
                var r = indice.get(v);
                if (r != null) resultado.addAll(r);
            }
            return resultado;
        }

        private static Map<String, Set<String>> indexar(
                List<DtosGescom.Articulo> articulos,
                java.util.function.Function<DtosGescom.Articulo, String> campo) {
            var indice = new HashMap<String, Set<String>>();
            for (var a : articulos) {
                var valor = campo.apply(a);
                if (valor != null) {
                    indice.computeIfAbsent(valor, k -> new java.util.HashSet<>()).add(a.codigo());
                }
            }
            return indice;
        }
    }
}
