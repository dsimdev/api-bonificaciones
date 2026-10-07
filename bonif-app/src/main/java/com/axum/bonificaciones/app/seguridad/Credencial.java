package com.axum.bonificaciones.app.seguridad;

/**
 * Una api-key validada.
 *
 * @param alcance VALORIZACION es la que va en la tienda: solo puede valorizar. ADMIN ademas lee el
 *     catalogo y no sale del back-office. Separarlas es lo que limita el dano de una key filtrada
 *     en un navegador -- /criterios es la estructura comercial completa de la distribuidora, y un
 *     competidor paga por eso
 */
public record Credencial(long id, String tenant, Alcance alcance, String descripcion) {

    public enum Alcance {
        VALORIZACION,
        ADMIN;

        public boolean puedeLeerElCatalogo() {
            return this == ADMIN;
        }
    }
}
