package com.axum.bonificaciones.core.model;

/**
 * Algo que resolvimos nosotros porque el pedido no lo traia.
 *
 * La regla dura del gateway es que nunca hay un default silencioso: si falta un dato y lo
 * completamos, el consumidor tiene que verlo en la respuesta. Un precio calculado sobre una
 * suposicion que nadie vio es un reclamo esperando.
 */
public record Supuesto(String codigo, String mensaje) {

    /**
     * Ni precio ni lista: el ERP uso la lista que tiene asignada el cliente.
     *
     * @param items los codigos que quedaron sin precio propio. Van en el mensaje porque en un
     *              carrito mixto lo que importa es CUALES, no que haya alguno
     */
    public static Supuesto sinPrecioNiLista(java.util.List<String> items) {
        return new Supuesto("SIN_PRECIO_NI_LISTA",
                "Estos items no traen precioUnitario y el pedido no trae listaPrecio, asi que el "
                        + "ERP los valorizo con la lista asignada al cliente: "
                        + String.join(", ", items)
                        + ". El neto de esos items puede no coincidir con el que muestra la tienda.");
    }

    /**
     * Vinieron el precio y la lista. Gana el precio, verificado en vivo contra dyssa: con los dos
     * presentes el ERP valoriza con PrecioUnitario e ignora la lista para el importe.
     *
     * No es un error, pero la lista sobra: con precio propio no cambia el descuento, tampoco en
     * los criterios con condicion ListaPrecioVenta (verificado contra dyssa el 2026-10-08,
     * criterio 610: mismo 12% sin lista, con lista 2 y con lista 3). Si el consumidor cree que
     * esta valorizando por lista y le llega un neto que no entiende, esta es la explicacion.
     */
    public static Supuesto precioYListaJuntos() {
        return new Supuesto("PRECIO_Y_LISTA_JUNTOS",
                "El pedido trae listaPrecio, pero todos los items traen precioUnitario: el ERP "
                        + "uso los precios y la lista no se uso. Con precio propio no hace falta "
                        + "mandarla: no cambia el descuento.");
    }
}
