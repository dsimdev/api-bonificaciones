package com.axum.bonificaciones.app.dominio;

/** Excepcion con codigo de dominio. El crudo de la fuente viaja aparte, nunca mezclado. */
public class ErrorDeGateway extends RuntimeException {

    private final CodigoDeError codigo;
    private final String crudo;

    public ErrorDeGateway(CodigoDeError codigo, String mensaje) {
        this(codigo, mensaje, null);
    }

    public ErrorDeGateway(CodigoDeError codigo, String mensaje, String crudo) {
        super(mensaje);
        this.codigo = codigo;
        this.crudo = crudo;
    }

    public CodigoDeError codigo() {
        return codigo;
    }

    /** La respuesta tal cual la mando la fuente, cuando no se pudo clasificar. Puede ser null. */
    public String crudo() {
        return crudo;
    }
}
