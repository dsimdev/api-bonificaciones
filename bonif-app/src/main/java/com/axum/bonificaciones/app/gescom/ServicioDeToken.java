package com.axum.bonificaciones.app.gescom;

import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras.Gescom;
import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Mintea y cachea el token de Keycloak de cada distribuidora.
 *
 * El token dura 5 minutos. Se cachea 4 para no usarlo nunca contra el filo del vencimiento, y se
 * mintea en el mismo proceso que lo usa: un token relayado desde afuera llega vencido (es el
 * problema que ya se habia visto probando la API a mano).
 *
 * Clave del cache = el tenant. Es deliberado y hay un test que lo cubre: devolverle a una
 * distribuidora el token de otra seria servir datos comerciales cruzados.
 */
@Service
public class ServicioDeToken {

    private static final String CLIENT_ID = "gcw-web-api";

    private final RestClient http;
    private final String urlDeAuth;
    private final Cache<String, String> tokens;

    ServicioDeToken(RestClient http,
                    @org.springframework.beans.factory.annotation.Value(
                            "${bonificaciones.gescom.url-auth:https://auth.gescom.online}")
                    String urlDeAuth) {
        this.http = http;
        this.urlDeAuth = urlDeAuth;
        this.tokens = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(4)).build();
    }

    public String token(String tenant, Gescom config) {
        return tokens.get(tenant, t -> mintear(config));
    }

    /**
     * Mintea sin tocar el cache. Es para VERIFICAR credenciales nuevas: si usara el cache, una
     * distribuidora que ya tiene un token vigente daria por buenas unas credenciales nuevas que
     * en realidad estan mal -- justo lo contrario de lo que el alta tiene que detectar.
     */
    public String tokenSinCache(Gescom config) {
        return mintear(config);
    }

    /** Descarta el token cacheado de un tenant y fuerza el proximo mint. */
    public void olvidar(String tenant) {
        tokens.invalidate(tenant);
    }

    private String mintear(Gescom config) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", CLIENT_ID);
        form.add("grant_type", "password");
        form.add("username", config.usuario());
        form.add("password", config.clave());

        try {
            var respuesta = http.post()
                    .uri(urlDeAuth + "/realms/" + config.realm() + "/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(RespuestaDeToken.class);

            if (respuesta == null || respuesta.access_token() == null) {
                throw new ErrorDeGateway(CodigoDeError.CREDENCIALES_INVALIDAS,
                        "Keycloak respondio sin access_token para el realm " + config.realm());
            }
            return respuesta.access_token();
        } catch (RestClientResponseException e) {
            // El cuerpo de un error de Keycloak puede traer el usuario: no se loguea ni se
            // propaga. Solo el realm, que no es secreto.
            throw new ErrorDeGateway(CodigoDeError.CREDENCIALES_INVALIDAS,
                    "Keycloak rechazo las credenciales del realm " + config.realm()
                            + " (HTTP " + e.getStatusCode().value() + ")");
        } catch (ErrorDeGateway e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ErrorDeGateway(CodigoDeError.FUENTE_NO_DISPONIBLE,
                    "No se pudo contactar a Keycloak para el realm " + config.realm());
        }
    }

    private record RespuestaDeToken(String access_token, Integer expires_in) {}
}
