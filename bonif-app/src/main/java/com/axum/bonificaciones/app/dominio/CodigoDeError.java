package com.axum.bonificaciones.app.dominio;

/**
 * Codigos de dominio del gateway.
 *
 * El integrador tiene que poder distinguir "el cliente no existe" de "el ERP esta caido" de
 * "la distribuidora no esta configurada", y con el HTTP solo no alcanza. GESCOM colapsa casi
 * todo a {"errorCode":"0","message":"Error desconocido"}: traducir eso a algo accionable es
 * buena parte del valor de este servicio.
 */
public enum CodigoDeError {

    /** El tenant no esta en la configuracion. Se detecta antes de tocar la red. */
    TENANT_DESCONOCIDO(400),

    /** El tenant existe pero no tiene configurada la fuente que hace falta. */
    FUENTE_NO_CONFIGURADA(400),

    /** El pedido no pasa las validaciones (sin items, cantidad <= 0, etc.). */
    PEDIDO_INVALIDO(400),

    /** Usuario o clave de API de la distribuidora rechazados por Keycloak. */
    CREDENCIALES_INVALIDAS(502),

    /** La fuente no responde o tarda demasiado. Reintentable. */
    FUENTE_NO_DISPONIBLE(503),

    /**
     * La fuente respondio un error que no se puede clasificar -- tipicamente el generico de
     * GESCOM. Se devuelve con el crudo adjunto para que alguien lo pueda mirar.
     */
    FUENTE_ERROR_DESCONOCIDO(502),

    /**
     * La ruta no existe en el gateway de comandos (Unity.Exceptions.InvalidRegistrationException).
     * Es un error NUESTRO: pedimos un comando que no existe.
     */
    COMANDO_INEXISTENTE(500),

    /**
     * La respuesta de la fuente es internamente incoherente (el neto con descuento no se
     * corresponde con el neto y el descuento). No se devuelve un numero que no cierra.
     */
    RESPUESTA_INCOHERENTE(502);

    private final int http;

    CodigoDeError(int http) {
        this.http = http;
    }

    public int http() {
        return http;
    }
}
