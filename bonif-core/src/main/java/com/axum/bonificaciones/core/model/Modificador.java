package com.axum.bonificaciones.core.model;

import java.util.List;

/**
 * Lo que hace el criterio cuando aplica, y sobre que items cae.
 *
 * @param codigo               id del modificador dentro del criterio
 * @param tipo                 el tipo tal cual lo nombra el ERP ("DescuentoItem"). Se guarda en
 *                             crudo porque es el unico lugar donde se ve que un tipo nuevo apareci
 *                             -- la operacion, al ser sealed, no puede representarlo
 * @param descripcion          el texto del propio ERP ("Aplicar descuento en items")
 * @param operacion            que hace, interpretado. null si el tipo no se reconoce
 * @param condicionesDeDatos   dataConditionCodes: a que items cae. Vacio = a todos los que
 *                             califican. Un criterio puede tener varios modificadores apuntando a
 *                             condiciones distintas -- asi se arma "10% global y 5% en Pehuamar"
 * @param permiteSuperposicion allowOverlap: si apila con otros criterios sobre el mismo item
 * @param crudo                el configuracionJson original, sin interpretar
 */
public record Modificador(
        Integer codigo,
        String tipo,
        String descripcion,
        Operacion operacion,
        List<Integer> condicionesDeDatos,
        boolean permiteSuperposicion,
        String crudo) {

    public Modificador {
        condicionesDeDatos = condicionesDeDatos == null ? List.of() : List.copyOf(condicionesDeDatos);
    }

    /**
     * true cuando el ERP mando un tipo de modificador que no sabemos interpretar.
     *
     * No se descarta ni se finge un descuento de 0: queda visible. El unico tipo observado en el
     * catalogo real de dyssa es DescuentoItem, pero no hay swagger que garantice que sea el unico.
     */
    public boolean noReconocido() {
        return operacion == null;
    }
}
