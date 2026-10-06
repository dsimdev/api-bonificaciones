package com.axum.bonificaciones.core.model;

import java.util.List;

/**
 * Lo minimo para pedir una valorizacion. No lleva identificador: el GUID que exige GESCOM lo
 * genera el conector, uno por llamada, para que un reintento no dependa de que el consumidor se
 * acuerde de mandarlo.
 */
public record PedidoAValorizar(
        String codigoCliente,
        String codigoListaPrecio,
        List<ItemAValorizar> items) {

    public PedidoAValorizar {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
