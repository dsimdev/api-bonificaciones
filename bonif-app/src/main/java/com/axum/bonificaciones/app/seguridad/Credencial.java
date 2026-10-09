package com.axum.bonificaciones.app.seguridad;

/**
 * Una api-key validada.
 *
 * @param alcance VALORIZACION valoriza y lee el catalogo de criterios (la tienda lo necesita para
 *     armar la pagina de bonificaciones). ADMIN ademas accede a la administracion. La llamada de
 *     la tienda va server-side desde v0.6.3, asi que la clave ya no esta expuesta en un navegador.
 */
public record Credencial(long id, String tenant, Alcance alcance, String descripcion) {

    public enum Alcance {
        VALORIZACION,
        ADMIN;

        public boolean puedeLeerElCatalogo() {
            return true;
        }
    }
}
