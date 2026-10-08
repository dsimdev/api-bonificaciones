package com.axum.bonificaciones.app.config;

import com.axum.bonificaciones.core.model.Fuente;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Las distribuidoras (tenants) que el gateway sabe consultar, con las fuentes configuradas de
 * cada una.
 *
 * Hay una configuracion por fuente y no un campo "erp", porque cada fuente tiene su propia forma
 * de autenticarse. Hoy la unica es GESCOM; el gateway de Axum quedo fuera de alcance el 2026-10-07
 * porque la tienda lo consume directo.
 *
 * Las credenciales NO van en el yml versionado: el yml solo referencia variables de entorno
 * (ver application.yml). Lo que se guarda es usuario y clave -- que NO vencen; el token de
 * Keycloak dura 5 minutos y lo mintea el gateway en el mismo proceso que lo usa.
 */
@ConfigurationProperties(prefix = "bonificaciones")
@ConditionalOnProperty(name = "bonificaciones.distribuidoras-en-base", havingValue = "false")
public record ConfiguracionDeDistribuidoras(Map<String, Distribuidora> distribuidoras)
        implements Distribuidoras {

    @Override
    public String origen() {
        return "CONFIGURACION";
    }

    @Override
    public List<String> codigosActivos() {
        return distribuidoras.keySet().stream().sorted().toList();
    }


    public ConfiguracionDeDistribuidoras {
        distribuidoras = distribuidoras == null ? Map.of() : Map.copyOf(distribuidoras);
    }

    public Set<String> tenants() {
        return distribuidoras.keySet();
    }

    @Override
    public Distribuidora requerir(String tenant) {
        var d = distribuidoras.get(tenant);
        if (d == null) {
            throw new DistribuidoraDesconocidaException(tenant);
        }
        return d;
    }

    public record Distribuidora(Gescom gescom) {

        /** Que fuentes quedaron efectivamente configuradas. Lo informa /health. */
        public List<Fuente> fuentes() {
            var fuentes = new ArrayList<Fuente>();
            if (gescom != null) fuentes.add(Fuente.GESCOM);
            return List.copyOf(fuentes);
        }
    }

    /**
     * @param host    base del gateway de comandos, ej. https://dyssa.gescom.online
     * @param realm   realm de Keycloak, ej. gcw-dyssa
     * @param usuario usuario de API (client gcw-web-api)
     * @param clave   clave de ese usuario
     */
    public record Gescom(String host, String realm, String usuario, String clave) {
        @Override
        public String toString() {
            return "Gescom[host=" + host + ", realm=" + realm + ", usuario=" + usuario + ", clave=***]";
        }
    }

    public static class DistribuidoraDesconocidaException extends RuntimeException {
        public DistribuidoraDesconocidaException(String tenant) {
            super("Distribuidora no configurada: " + tenant);
        }
    }
}
