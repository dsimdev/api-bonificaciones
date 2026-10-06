package com.axum.bonificaciones.core.model;

import java.math.BigDecimal;

/**
 * Un item del pedido a valorizar, en nuestro vocabulario.
 *
 * El mapeo a los nombres de la fuente vive en el conector: GESCOM lo quiere como CodigoItem
 * (NO CodigoArticulo -- con el nombre equivocado el item se ignora en silencio y la respuesta
 * es "Error desconocido").
 */
public record ItemAValorizar(
        String codigo,
        BigDecimal cantidad,
        String unidad,
        BigDecimal unidadFactor) {
}
