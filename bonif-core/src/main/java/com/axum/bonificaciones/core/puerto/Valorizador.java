package com.axum.bonificaciones.core.puerto;

import com.axum.bonificaciones.core.model.PedidoAValorizar;
import com.axum.bonificaciones.core.model.Valorizacion;

/**
 * Puerto de valorizacion. Lo implementa un conector por fuente.
 *
 * GESCOM delega en su motor (eval-pedido). Axum no tiene motor, asi que su implementacion va a
 * evaluar aca y devolver CalculadoPor.GATEWAY.
 */
public interface Valorizador {

    Valorizacion valorizar(String tenant, PedidoAValorizar pedido);
}
