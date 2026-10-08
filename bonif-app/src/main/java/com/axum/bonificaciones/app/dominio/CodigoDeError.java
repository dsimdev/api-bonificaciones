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
    RESPUESTA_INCOHERENTE(502),

    /**
     * La fuente rechazo el pedido por validacion de modelo: falta un dato o uno es invalido.
     *
     * Es error del CONSUMIDOR, no de la fuente. GESCOM tapa los errores de runtime con un
     * generico, pero la validacion de modelo SI informa: cuando informa, devolver 502 le estaria
     * diciendo al consumidor "reintenta" algo que nunca va a andar.
     */
    PEDIDO_RECHAZADO_POR_LA_FUENTE(400),

    /** El codigo de cliente no existe en el ERP de esa distribuidora. */
    CLIENTE_INEXISTENTE(400),

    /** Ya hay una distribuidora con ese codigo. */
    DISTRIBUIDORA_YA_EXISTE(409),

    /**
     * GESCOM rechazo las credenciales que se estan dando de alta. Es error de QUIEN CARGA (copio
     * mal del Postman), no de la fuente: por eso 400 y no 502.
     */
    CREDENCIALES_RECHAZADAS(400),

    /** Sesion invalida o vencida, o usuario y clave incorrectos. */
    NO_AUTORIZADO(401),

    /** Ya hay un usuario con ese nombre. */
    USUARIO_YA_EXISTE(409),

    /** No existe ese usuario. */
    USUARIO_INEXISTENTE(404),

    /** La clave no alcanza para lo que se esta pidiendo. */
    ALCANCE_INSUFICIENTE(403),

    /** Demasiados intentos fallidos de autenticacion para ese tenant. */
    DEMASIADOS_INTENTOS(429),

    /**
     * Administrar esta deshabilitado porque falta CIFRADO_KEY. Preferible fallar explicito a
     * guardar mil claves de produccion sin cifrar.
     */
    ADMINISTRACION_DESHABILITADA(503),

    /** Algo que no se esperaba. El detalle queda en el log del servidor, no en la respuesta. */
    ERROR_INTERNO(500);

    private final int http;

    CodigoDeError(int http) {
        this.http = http;
    }

    public int http() {
        return http;
    }
}
