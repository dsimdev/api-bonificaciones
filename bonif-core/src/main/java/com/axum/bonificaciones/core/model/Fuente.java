package com.axum.bonificaciones.core.model;

/**
 * De donde sale la informacion. NO son "ERPs intercambiables": cada fuente aporta cosas
 * distintas y no todas aportan lo mismo.
 *
 * - GESCOM: los criterios de venta (get-promociones) y, sobre todo, el motor que los APLICA
 *           (eval-pedido). Verificado en vivo.
 * - AXUM:   atributos de cliente y articulo, listas de precio, y un endpoint de bonificaciones
 *           pedido por el usuario al equipo del gateway. Falta ver su contrato: si entrega las
 *           definiciones (y entonces es otra fuente de criterios) o ademas las evalua.
 *
 * Chess entra mas adelante y todavia no sabemos que aporta.
 */
public enum Fuente {
    GESCOM,
    AXUM
}
