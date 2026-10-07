package com.axum.bonificaciones.core.model;

import java.util.List;

/**
 * Cuando aplica un criterio.
 *
 * @param tipoCrudo        el tipo tal cual lo nombra el ERP. Se guarda porque cuando el tipo cae
 *                         en DESCONOCIDA es el unico lugar donde se ve QUE apareci
 * @param codigo           id de la condicion DENTRO del criterio. Es lo que referencian el
 *                         codigoCondicionPrincipal, las condicionesHijas y los dataConditionCodes
 *                         de los modificadores. No es el id global de la fila
 * @param descripcion      el texto que el propio ERP le pone ("La venta tiene items de una o mas
 *                         marcas"). Sirve para explicar sin que tengamos que redactarlo nosotros
 * @param tipo             que atributo mira
 * @param valores          los valores que hacen match (codigos de marca, tags, etc.)
 * @param invertida        si true, aplica a lo que NO esta en {@code valores}
 * @param cantidadMinima   requiredQuantity: cantidad que hay que comprar para que dispare
 * @param condicionesHijas para TODAS/ALGUNA: los codigos de las condiciones que combina
 * @param crudo            el configuracionJson original, sin interpretar
 */
public record Condicion(
        Integer codigo,
        String tipoCrudo,
        String descripcion,
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

    /** TODAS y ALGUNA no miran ningun atributo: combinan otras condiciones. */
    public boolean esCombinador() {
        return tipo == TipoCondicion.TODAS || tipo == TipoCondicion.ALGUNA;
    }
}
