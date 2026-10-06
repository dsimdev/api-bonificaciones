package com.axum.bonificaciones.core.model;

import java.util.List;

/**
 * Cuando aplica un criterio.
 *
 * @param codigo        id de la condicion dentro del criterio; los modificadores la referencian
 * @param tipo          que atributo mira
 * @param valores       los valores que hacen match (codigos de marca, tags, etc.)
 * @param invertida     si true, aplica a lo que NO esta en {@code valores}
 * @param cantidadMinima cantidad que hay que comprar para que dispare, o null
 * @param condicionesHijas para TODAS/ALGUNA: los codigos de las condiciones que combina
 * @param crudo         el configuracionJson original del ERP, sin interpretar
 */
public record Condicion(
        Integer codigo,
        TipoCondicion tipo,
        List<String> valores,
        boolean invertida,
        Integer cantidadMinima,
        List<Integer> condicionesHijas,
        String crudo) {

    public Condicion {
        valores = valores == null ? List.of() : List.copyOf(valores);
        condicionesHijas = condicionesHijas == null ? List.of() : List.copyOf(condicionesHijas);
    }
}
