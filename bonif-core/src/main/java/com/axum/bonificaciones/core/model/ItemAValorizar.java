package com.axum.bonificaciones.core.model;

import java.math.BigDecimal;

/**
 * Un item del pedido a valorizar, en nuestro vocabulario.
 *
 * El mapeo a los nombres de la fuente vive en el conector: GESCOM lo quiere como CodigoItem
 * (NO CodigoArticulo -- con el nombre equivocado el item se ignora en silencio y la respuesta
 * es "Error desconocido").
 *
 * @param precioUnitario precio POR UNIDAD que pone el consumidor, o null para que lo ponga el ERP
 *                       desde la lista. Verificado en vivo (dyssa, 2026-10-08): el ERP valoriza
 *                       con este precio, sigue aplicando los mismos criterios y el descuento lo
 *                       sigue calculando el, asi que la respuesta sigue siendo calculadoPor=ERP.
 */
public record ItemAValorizar(
        String codigo,
        BigDecimal cantidad,
        String unidad,
        BigDecimal unidadFactor,
        BigDecimal precioUnitario) {

    /** @return true si el consumidor puso el precio de este item */
    public boolean traePrecio() {
        return precioUnitario != null;
    }
}
