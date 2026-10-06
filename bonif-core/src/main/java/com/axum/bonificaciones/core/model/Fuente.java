package com.axum.bonificaciones.core.model;

/**
 * De donde sale la informacion. NO son "ERPs intercambiables": cada fuente aporta cosas
 * distintas y no todas saben de criterios.
 *
 * - GESCOM: los criterios de venta (get-promociones) y el motor que los aplica (eval-pedido).
 * - AXUM:   atributos de cliente y articulo y listas de precio. Su gateway NO expone
 *           promociones ni descuentos -- verificado contra la doc de la API publica
 *           (C:\Dev\docs\axum\integracion-axum.md, shapes reales de /clientes y /articulos).
 *
 * Chess entra mas adelante y todavia no sabemos que aporta.
 */
public enum Fuente {
    GESCOM,
    AXUM
}
