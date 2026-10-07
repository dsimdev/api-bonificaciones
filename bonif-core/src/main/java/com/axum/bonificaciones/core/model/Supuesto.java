package com.axum.bonificaciones.core.model;

/**
 * Algo que resolvimos nosotros porque el pedido no lo traia.
 *
 * La regla dura del gateway es que nunca hay un default silencioso: si falta un dato y lo
 * completamos, el consumidor tiene que verlo en la respuesta. Un precio calculado sobre una
 * suposicion que nadie vio es un reclamo esperando.
 */
public record Supuesto(String codigo, String mensaje) {

    /** No vino la lista de precios, asi que el ERP uso la que tiene asignada el cliente. */
    public static Supuesto listaDePrecioNoEnviada() {
        return new Supuesto("LISTA_PRECIO_NO_ENVIADA",
                "No se envio listaPrecio: se uso la lista asignada al cliente en el ERP. "
                        + "La lista cambia el PRECIO, asi que el neto puede no coincidir con el "
                        + "que muestra la tienda.");
    }
}
