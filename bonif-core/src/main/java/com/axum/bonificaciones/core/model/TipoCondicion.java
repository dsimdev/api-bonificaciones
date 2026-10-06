package com.axum.bonificaciones.core.model;

/**
 * Que mira una condicion para decidir si el criterio aplica.
 *
 * Los nombres salen de los {@code condicion.tipo} observados en get-promociones de GESCOM
 * (C:\Dev\docs\gescom\eval-pedido.md). DESCONOCIDA es deliberada: un tipo nuevo del ERP no debe
 * romper la lectura del catalogo, tiene que llegar visible al consumidor.
 */
public enum TipoCondicion {
    CODIGO_CLIENTE,
    TAG_CLIENTE,
    SUBRAMO_CLIENTE,
    CODIGO_ITEM,
    MARCA_ARTICULO,
    PROVEEDOR_ARTICULO,
    LINEA_ARTICULO,
    RUBRO_ITEM,
    FAMILIA_ARTICULO,
    CALIBRE_ARTICULO,
    TAG_ITEM,
    TODAS,
    ALGUNA,
    DESCONOCIDA
}
