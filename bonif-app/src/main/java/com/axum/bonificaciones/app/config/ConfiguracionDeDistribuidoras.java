package com.axum.bonificaciones.app.config;

import com.axum.bonificaciones.core.model.Fuente;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Las distribuidoras (tenants) que el gateway sabe consultar, con las fuentes configuradas de
 * cada una.
 *
 * Una distribuidora puede tener mas de una fuente a la vez y con credenciales distintas: los
 * criterios salen de GESCOM y los atributos de cliente/articulo pueden salir del gateway de Axum.
 * Por eso no hay un campo "erp": hay una configuracion por fuente, y cada una tiene su forma.
 *
 * Las credenciales NO van en el yml versionado: el yml solo referencia variables de entorno
 * (ver application.yml). Lo que se guarda es usuario y clave -- que NO vencen; el token de
 * Keycloak dura 5 minutos y lo mintea el gateway en el mismo proceso que lo usa.
 */
@ConfigurationProperties(prefix = "bonificaciones")
public record ConfiguracionDeDistribuidoras(Map<String, Distribuidora> distribuidoras) {

    public ConfiguracionDeDistribuidoras {
        distribuidoras = distribuidoras == null ? Map.of() : Map.copyOf(distribuidoras);
    }

    public Set<String> tenants() {
        return distribuidoras.keySet();
    }

    public Distribuidora requerir(String tenant) {
        var d = distribuidoras.get(tenant);
        if (d == null) {
            throw new DistribuidoraDesconocidaException(tenant);
        }
        return d;
    }

    public record Distribuidora(Gescom gescom, Axum axum) {

        /** Que fuentes quedaron efectivamente configuradas. Lo informa /health. */
        public List<Fuente> fuentes() {
            var fuentes = new ArrayList<Fuente>();
            if (gescom != null) fuentes.add(Fuente.GESCOM);
            if (axum != null) fuentes.add(Fuente.AXUM);
            return List.copyOf(fuentes);
        }
    }

    /**
     * @param host    base del gateway de comandos, ej. https://dyssa.gescom.online
     * @param realm   realm de Keycloak, ej. gcw-dyssa
     * @param usuario usuario de API (client gcw-web-api)
     * @param clave   clave de ese usuario
     */
    public record Gescom(String host, String realm, String usuario, String clave) {}

    /**
     * @param tenant el tenant en el gateway de Axum (va en la ruta: /{tenant}/api/v1/...)
     * @param apiKey header x-api-key
     */
    public record Axum(String tenant, String apiKey) {}

    public static class DistribuidoraDesconocidaException extends RuntimeException {
        public DistribuidoraDesconocidaException(String tenant) {
            super("Distribuidora no configurada: " + tenant);
        }
    }
}
