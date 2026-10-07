package com.axum.bonificaciones.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Un criterio de venta normalizado: lo que el negocio llama "bonificacion" o "promocion".
 *
 * En GESCOM no hay endpoint de bonificaciones: esto es una entrada de get-promociones, con los
 * configuracionJson ya parseados.
 *
 * @param descripcion               el texto que escribio quien cargo el criterio. OJO: es texto
 *                                  libre y puede no coincidir con el descuento real -- en dyssa
 *                                  hay un criterio que se llama "DESCUENTO 20% EN CADENAS" y
 *                                  aplica 15%. Para el numero, el modificador; la descripcion es
 *                                  para humanos
 * @param codigoCondicionPrincipal  por donde ARRANCA la evaluacion. Apunta al codigo de la
 *                                  condicion raiz (tipicamente un TODAS). Sin esto no se sabe que
 *                                  condiciones estan realmente en juego
 * @param orden                     precedencia del criterio dentro del catalogo
 */
public record Criterio(
        Fuente fuente,
        String distribuidora,
        String id,
        String nombre,
        String descripcion,
        boolean activo,
        Vigencia vigencia,
        Integer codigoCondicionPrincipal,
        Integer orden,
        List<Condicion> condiciones,
        List<Modificador> modificadores,
        List<String> clientes) {

    public Criterio {
        condiciones = condiciones == null ? List.of() : List.copyOf(condiciones);
        modificadores = modificadores == null ? List.of() : List.copyOf(modificadores);
        clientes = clientes == null ? List.of() : List.copyOf(clientes);
    }

    /** Vacio = el criterio no esta limitado a clientes puntuales (lo deciden las condiciones). */
    public boolean limitadoAClientes() {
        return !clientes.isEmpty();
    }

    public Optional<Condicion> condicion(Integer codigo) {
        return condiciones.stream().filter(c -> codigo != null && codigo.equals(c.codigo())).findFirst();
    }

    /**
     * Las condiciones que de verdad estan en juego: las alcanzables caminando desde
     * {@code codigoCondicionPrincipal} por las condicionesHijas.
     *
     * NO es lo mismo que {@code condiciones()}. En el catalogo real de dyssa hay condiciones
     * HUERFANAS: existen en la lista pero ningun combinador las referencia, asi que no participan
     * de la evaluacion (p.ej. el criterio 294 define una condicion de linea de articulo y su
     * TODAS solo referencia la de cliente). Mostrarlas como "por que aplico" seria mentir.
     */
    public List<Condicion> condicionesEnJuego() {
        if (codigoCondicionPrincipal == null) return condiciones;

        var porCodigo = condiciones.stream()
                .filter(c -> c.codigo() != null)
                .collect(Collectors.toMap(Condicion::codigo, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        var vistos = new LinkedHashSet<Integer>();
        var pendientes = new ArrayList<Integer>();
        pendientes.add(codigoCondicionPrincipal);

        while (!pendientes.isEmpty()) {
            var codigo = pendientes.remove(0);
            // El catalogo real tiene ciclos potenciales (una condicion Any que se referencia a si
            // misma via el padre: ver criterio 2, donde el Any 104 incluye la 101 que tambien
            // cuelga del All). Sin este corte el recorrido no termina.
            if (!vistos.add(codigo)) continue;
            var condicion = porCodigo.get(codigo);
            if (condicion != null) {
                pendientes.addAll(condicion.condicionesHijas());
            }
        }

        return vistos.stream().map(porCodigo::get).filter(c -> c != null).toList();
    }

    /** Las condiciones en juego que miran un atributo de verdad, sin los TODAS/ALGUNA. */
    public List<Condicion> condicionesHoja() {
        return condicionesEnJuego().stream().filter(c -> !c.esCombinador()).toList();
    }

    /**
     * Las condiciones a las que apunta un modificador por sus dataConditionCodes. Si no apunta a
     * ninguna, el descuento cae sobre todo lo que califique, asi que valen todas las hojas.
     */
    public List<Condicion> condicionesDe(Modificador modificador) {
        if (modificador.condicionesDeDatos().isEmpty()) return condicionesHoja();
        Map<Integer, Condicion> porCodigo = condiciones.stream()
                .filter(c -> c.codigo() != null)
                .collect(Collectors.toMap(Condicion::codigo, Function.identity(), (a, b) -> a));
        return modificador.condicionesDeDatos().stream()
                .map(porCodigo::get)
                .filter(c -> c != null)
                .toList();
    }
}
