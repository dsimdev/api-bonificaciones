package com.axum.bonificaciones.app.gescom;

import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras.Gescom;
import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Habla con el gateway de comandos de GESCOM y traduce sus errores a codigos de dominio.
 *
 * Toda la rareza de GESCOM muere aca: el prefijo /data/cmd, el servicio en la ruta (ventas vs
 * inventario), el token de 5 minutos, y un manejo de errores que colapsa casi todo a
 * {"errorCode":"0","message":"Error desconocido"}.
 */
@Component
public class ClienteGescom {

    /**
     * Una ruta no registrada responde con esta excepcion de Unity. Sirve para distinguir
     * "el comando no existe" (error nuestro) de "el comando fallo" (error de la fuente), que es
     * la unica forma de descubrir endpoints en esta API.
     */
    private static final String UNITY_NO_REGISTRADO = "InvalidRegistrationException";

    /** El mensaje con el que GESCOM tapa todos los errores de runtime. */
    private static final String MENSAJE_GENERICO = "Error desconocido";

    private static final Logger log = LoggerFactory.getLogger(ClienteGescom.class);

    private final RestClient http;
    private final ServicioDeToken tokens;
    private final ObjectMapper json;

    ClienteGescom(RestClient http, ServicioDeToken tokens, ObjectMapper json) {
        this.http = http;
        this.tokens = tokens;
        this.json = json;
    }

    public <T> T get(String tenant, Gescom config, String servicio, String comando,
                     ParameterizedTypeReference<T> tipo) {
        return ejecutar(config, servicio, comando, () -> http.get()
                .uri(url(config, servicio, comando))
                .header("Authorization", "Bearer " + tokens.token(tenant, config))
                .retrieve()
                .body(tipo));
    }

    /**
     * Como { #get}, pero minteando un token nuevo en vez de usar el cacheado. Para verificar
     * credenciales que todavia no estan guardadas.
     */
    public <T> T getConCredenciales(Gescom config, String servicio, String comando,
                                    ParameterizedTypeReference<T> tipo) {
        return ejecutar(config, servicio, comando, () -> http.get()
                .uri(url(config, servicio, comando))
                .header("Authorization", "Bearer " + tokens.tokenSinCache(config))
                .retrieve()
                .body(tipo));
    }

    public <T> T post(String tenant, Gescom config, String servicio, String comando,
                      Object cuerpo, Class<T> tipo) {
        return ejecutar(config, servicio, comando, () -> http.post()
                .uri(url(config, servicio, comando))
                .header("Authorization", "Bearer " + tokens.token(tenant, config))
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .retrieve()
                .body(tipo));
    }

    /**
     * Como {@link #post}, pero devolviendo tambien el cuerpo crudo que mando GESCOM.
     *
     * Existe para el diagnostico del panel. Cuando una tienda reclama un descuento, hay tres
     * sospechosos -- el ERP, nuestra normalizacion y lo que muestra la tienda -- y sin el crudo
     * del ERP al lado del nuestro no se puede saber cual es. El contrato publico NO lo lleva: es
     * un dato de back-office y no se agrega a la respuesta que ve el navegador.
     *
     * Se pide como String y se parsea aca, en vez de dejar que lo haga el conversor del
     * RestClient, porque el cuerpo se necesita entero y sin tocar: leerlo dos veces de la misma
     * respuesta no se puede.
     */
    public <T> ConCrudo<T> postConCrudo(String tenant, Gescom config, String servicio,
                                        String comando, Object cuerpo, Class<T> tipo) {
        var enviado = aJson(cuerpo);
        var crudo = ejecutar(config, servicio, comando, () -> http.post()
                .uri(url(config, servicio, comando))
                .header("Authorization", "Bearer " + tokens.token(tenant, config))
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo)
                .retrieve()
                .body(String.class));

        if (crudo == null || crudo.isBlank()) {
            return new ConCrudo<>(null, crudo, enviado);
        }
        try {
            return new ConCrudo<>(json.readValue(crudo, tipo), crudo, enviado);
        } catch (JsonProcessingException e) {
            // Un 200 con un cuerpo que no se puede parsear es tan util de ver como un error: el
            // crudo viaja igual en vez de perderse en un stack trace.
            throw new ErrorDeGateway(CodigoDeError.FUENTE_ERROR_DESCONOCIDO,
                    "GESCOM respondio algo que no se pudo interpretar en " + servicio + "/"
                            + comando, crudo);
        }
    }

    /**
     * El cuerpo del pedido como JSON, para poder mostrarlo.
     *
     * Devuelve String y no el DTO: los DTO de GESCOM no salen de este paquete (ver DtosGescom), y
     * el diagnostico necesita justamente los nombres de campo del ERP -- CodigoItem y no codigo --
     * para que se pueda pegar en Postman tal cual.
     */
    private String aJson(Object cuerpo) {
        try {
            return json.writeValueAsString(cuerpo);
        } catch (JsonProcessingException e) {
            // Que no se pueda mostrar el pedido no puede voltear el pedido.
            log.warn("No se pudo serializar el pedido para el diagnostico: {}", e.getMessage());
            return null;
        }
    }

    /**
     * @param cuerpo  null cuando GESCOM respondio vacio; el crudo viaja igual
     * @param crudo   lo que contesto GESCOM, sin tocar
     * @param enviado lo que le mandamos, con sus nombres de campo
     */
    public record ConCrudo<T>(T cuerpo, String crudo, String enviado) {}

    private String url(Gescom config, String servicio, String comando) {
        return config.host() + "/data/cmd/" + servicio + "/api/v1/" + comando;
    }

    private <T> T ejecutar(Gescom config, String servicio, String comando,
                           java.util.function.Supplier<T> llamada) {
        try {
            return llamada.get();
        } catch (ErrorDeGateway e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw clasificar(e, servicio, comando);
        } catch (ResourceAccessException e) {
            log.warn("Fallo de red hacia GESCOM en {}/{}: {}", servicio, comando, e.toString());
            throw new ErrorDeGateway(CodigoDeError.FUENTE_NO_DISPONIBLE,
                    "GESCOM no respondio a tiempo (" + servicio + "/" + comando + ")");
        }
    }

    private ErrorDeGateway clasificar(RestClientResponseException e, String servicio, String comando) {
        var cuerpo = e.getResponseBodyAsString();

        if (cuerpo != null && cuerpo.contains(UNITY_NO_REGISTRADO)) {
            // Error nuestro, no de la distribuidora: pedimos un comando que no existe.
            return new ErrorDeGateway(CodigoDeError.COMANDO_INEXISTENTE,
                    "El comando " + servicio + "/" + comando + " no existe en GESCOM", cuerpo);
        }
        if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
            return new ErrorDeGateway(CodigoDeError.CREDENCIALES_INVALIDAS,
                    "GESCOM rechazo el token en " + servicio + "/" + comando);
        }
        if (e.getStatusCode().is5xxServerError()) {
            return new ErrorDeGateway(CodigoDeError.FUENTE_NO_DISPONIBLE,
                    "GESCOM respondio " + e.getStatusCode().value()
                            + " en " + servicio + "/" + comando, cuerpo);
        }
        // GESCOM colapsa los errores de RUNTIME en {"errorCode":"0","message":"Error desconocido"},
        // pero la VALIDACION DE MODELO si informa. Cuando informa, el pedido esta mal y es del
        // consumidor: devolver 502 ahi le estaria diciendo "reintenta" algo que nunca va a andar.
        var mensaje = mensajeDe(cuerpo);
        if (mensaje != null && !mensaje.equalsIgnoreCase(MENSAJE_GENERICO)) {
            if (mensaje.contains("CodigoCliente")) {
                return new ErrorDeGateway(CodigoDeError.CLIENTE_INEXISTENTE, mensaje, cuerpo);
            }
            return new ErrorDeGateway(CodigoDeError.PEDIDO_RECHAZADO_POR_LA_FUENTE, mensaje, cuerpo);
        }

        // Lo que queda es el generico. No se puede clasificar mas: se devuelve con el crudo para
        // que alguien lo pueda mirar, en vez de tragarselo.
        return new ErrorDeGateway(CodigoDeError.FUENTE_ERROR_DESCONOCIDO,
                "GESCOM devolvio un error no clasificable en " + servicio + "/" + comando, cuerpo);
    }

    /**
     * El "message" del JSON de error. Se saca con regex y no parseando: el cuerpo de un error de
     * GESCOM no siempre es JSON valido, y un fallo del parser no puede tapar el error original.
     */
    private static final Pattern MENSAJE =
            Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** El "message" del cuerpo, que es lo unico util que manda GESCOM cuando manda algo. */
    private String mensajeDe(String cuerpo) {
        if (cuerpo == null) return null;
        var m = MENSAJE.matcher(cuerpo);
        return m.find() ? m.group(1) : null;
    }
}
