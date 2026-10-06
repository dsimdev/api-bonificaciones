package com.axum.bonificaciones.core.model;

/**
 * Quien produjo el numero. Va en toda respuesta de valorizacion y es parte del contrato, no un
 * detalle de debug: "lo dijo el ERP" y "lo calculamos nosotros" no valen lo mismo frente a un
 * reclamo.
 */
public enum CalculadoPor {
    /** Lo resolvio el motor de la fuente (GESCOM: eval-pedido). */
    ERP,
    /** Lo calculo este gateway con las definiciones de la fuente (Axum, que no tiene motor). */
    GATEWAY
}
