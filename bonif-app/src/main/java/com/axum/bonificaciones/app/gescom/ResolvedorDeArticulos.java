package com.axum.bonificaciones.app.gescom;

import com.axum.bonificaciones.core.model.Condicion;
import com.axum.bonificaciones.core.model.Criterio;
import com.axum.bonificaciones.core.model.TipoCondicion;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Dado un criterio y el indice de articulos, resuelve que codigos de articulo matchean las
 * condiciones de articulo del criterio.
 *
 * Interpreta el arbol de condiciones: TODAS = interseccion, ALGUNA = union. Las condiciones de
 * cliente (TAG_CLIENTE, CODIGO_CLIENTE, SUBRAMO_CLIENTE) no filtran articulos.
 *
 * Esto NO es reimplementar la evaluacion de criterios. La evaluacion es: dado un pedido concreto
 * (cliente + items + cantidades), que descuento le cae a cada linea — eso lo hace eval-pedido.
 * Lo que hacemos aca es resolver "a que articulos PODRIA aplicar este criterio", que es un join
 * contra el catalogo de articulos, no un calculo de descuento.
 */
public final class ResolvedorDeArticulos {

    private ResolvedorDeArticulos() {}

    /**
     * Los codigos de articulo que matchean las condiciones de articulo del criterio.
     *
     * Camina el arbol desde codigoCondicionPrincipal. Las condiciones de cliente se ignoran
     * (no filtran articulos). Si no hay condiciones de articulo, devuelve vacio (el criterio
     * aplica a todo, el consumidor no necesita la lista).
     */
    public static Set<String> resolver(Criterio criterio,
                                       ArticulosGescom.IndiceDeArticulos indice) {
        if (criterio.codigoCondicionPrincipal() == null) {
            return articulosDeLasHojas(criterio, indice);
        }

        var porCodigo = criterio.condiciones().stream()
                .filter(c -> c.codigo() != null)
                .collect(Collectors.toMap(Condicion::codigo, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        var raiz = porCodigo.get(criterio.codigoCondicionPrincipal());
        if (raiz == null) return articulosDeLasHojas(criterio, indice);

        // Memoizacion: GESCOM arma DAGs, no arboles — un nodo puede tener dos padres
        // (ej. TODAS(tag, ALGUNA(tag, marca, marca2), marca, marca2)). Sin memoizar, el segundo
        // acceso al nodo devuelve vacio y la interseccion mata el resultado.
        var memo = new HashMap<Integer, Set<String>>();
        return resolverNodo(raiz, porCodigo, indice, memo);
    }

    private static final Set<String> EN_CURSO = Set.of();

    private static Set<String> resolverNodo(
            Condicion nodo,
            Map<Integer, Condicion> porCodigo,
            ArticulosGescom.IndiceDeArticulos indice,
            Map<Integer, Set<String>> memo) {

        var previo = memo.get(nodo.codigo());
        if (previo == EN_CURSO) return null;
        if (previo != null) return previo;

        memo.put(nodo.codigo(), EN_CURSO);

        Set<String> resultado;
        if (nodo.esCombinador()) {
            resultado = resolverCombinador(nodo, porCodigo, indice, memo);
        } else if (indice.esCondicionDeArticulo(nodo)) {
            resultado = indice.articulosQueMatchean(nodo);
        } else {
            resultado = null;
        }

        if (resultado != null) {
            memo.put(nodo.codigo(), resultado);
        } else {
            memo.remove(nodo.codigo());
        }
        return resultado;
    }

    private static Set<String> resolverCombinador(
            Condicion combinador,
            Map<Integer, Condicion> porCodigo,
            ArticulosGescom.IndiceDeArticulos indice,
            Map<Integer, Set<String>> memo) {

        boolean esInterseccion = combinador.tipo() == TipoCondicion.TODAS;
        Set<String> resultado = null;

        for (var codigoHijo : combinador.condicionesHijas()) {
            var hijo = porCodigo.get(codigoHijo);
            if (hijo == null) continue;

            var articulosDelHijo = resolverNodo(hijo, porCodigo, indice, memo);

            if (articulosDelHijo == null) continue;

            if (resultado == null) {
                resultado = new HashSet<>(articulosDelHijo);
            } else if (esInterseccion) {
                resultado.retainAll(articulosDelHijo);
            } else {
                resultado.addAll(articulosDelHijo);
            }
        }

        return resultado;
    }

    /** Fallback si no hay arbol: union de todas las hojas de articulo. */
    private static Set<String> articulosDeLasHojas(
            Criterio criterio,
            ArticulosGescom.IndiceDeArticulos indice) {
        var resultado = new HashSet<String>();
        for (var c : criterio.condicionesHoja()) {
            if (indice.esCondicionDeArticulo(c)) {
                resultado.addAll(indice.articulosQueMatchean(c));
            }
        }
        return resultado;
    }

    /**
     * true si el criterio tiene al menos una condicion de articulo en sus hojas en juego.
     * Sirve para distinguir "aplica a todo" (no tiene) de "no pudimos resolver" (tiene pero
     * el indice no matcheo nada, ej. CALIBRE_ARTICULO).
     */
    public static boolean tieneCondicionesDeArticulo(Criterio criterio,
                                                      ArticulosGescom.IndiceDeArticulos indice) {
        return criterio.condicionesHoja().stream()
                .anyMatch(indice::esCondicionDeArticulo);
    }

    // --- Filtro por cliente ---

    /**
     * true si el criterio aplica a este cliente. Mira las condiciones de cliente del arbol:
     * CODIGO_CLIENTE, TAG_CLIENTE, SUBRAMO_CLIENTE.
     *
     * Si el criterio no tiene condiciones de cliente, aplica a todos.
     */
    public static boolean aplicaAlCliente(Criterio criterio, ClientesGescom.DatosDeCliente datosCliente) {
        // Primero: si el criterio lista clientes explicitos
        if (criterio.limitadoAClientes()) {
            return criterio.clientes().contains(datosCliente.codigo());
        }

        // Segundo: mirar las condiciones de cliente en las hojas en juego
        var condicionesDeCliente = criterio.condicionesHoja().stream()
                .filter(ResolvedorDeArticulos::esCondicionDeCliente)
                .toList();

        if (condicionesDeCliente.isEmpty()) return true;

        // Todas las condiciones de cliente tienen que matchear (estan dentro de un TODAS)
        return condicionesDeCliente.stream()
                .allMatch(c -> clienteMatchea(c, datosCliente));
    }

    private static boolean esCondicionDeCliente(Condicion c) {
        return c.tipo() == TipoCondicion.CODIGO_CLIENTE
                || c.tipo() == TipoCondicion.TAG_CLIENTE
                || c.tipo() == TipoCondicion.SUBRAMO_CLIENTE;
    }

    private static boolean clienteMatchea(Condicion c, ClientesGescom.DatosDeCliente datos) {
        boolean match = switch (c.tipo()) {
            case CODIGO_CLIENTE -> c.valores().contains(datos.codigo());
            case TAG_CLIENTE -> c.valores().stream().anyMatch(datos.tags()::contains);
            case SUBRAMO_CLIENTE -> c.valores().contains(datos.subramo());
            default -> true;
        };
        return c.invertida() ? !match : match;
    }
}
