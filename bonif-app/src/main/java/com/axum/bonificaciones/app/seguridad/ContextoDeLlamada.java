package com.axum.bonificaciones.app.seguridad;

/**
 * Quien esta haciendo esta llamada, para poder auditarlo.
 *
 * Es el motivo de tener usuarios en vez de una clave compartida: cuando una distribuidora quedo
 * mal cargada, se sabe a quien preguntarle.
 */
public final class ContextoDeLlamada {

    private static final ThreadLocal<String> USUARIO = new ThreadLocal<>();

    private ContextoDeLlamada() {}

    public static void establecer(String usuario) {
        USUARIO.set(usuario);
    }

    public static String usuario() {
        return USUARIO.get();
    }

    public static void limpiar() {
        USUARIO.remove();
    }
}
