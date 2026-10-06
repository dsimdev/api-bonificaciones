package com.axum.bonificaciones.app.config;

import com.axum.bonificaciones.core.model.Erp;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Las distribuidoras que el gateway sabe consultar, con su ERP y sus credenciales.
 *
 * Las credenciales NO van en el yml versionado: el yml solo referencia variables de entorno
 * (ver application.yml). El gateway guarda user/password porque el grant de Keycloak de GESCOM es
 * {@code password} y el token dura 5 minutos -- no hay forma de que el consumidor los relaye a
 * tiempo. Que eso sea asi es una decision abierta (ver docs/proyecto/decisiones-abiertas.md).
 */
@ConfigurationProperties(prefix = "bonificaciones")
public record ConfiguracionDeDistribuidoras(Map<String, Distribuidora> distribuidoras) {

    public ConfiguracionDeDistribuidoras {
        distribuidoras = distribuidoras == null ? Map.of() : Map.copyOf(distribuidoras);
    }

    public Set<String> codigos() {
        return distribuidoras.keySet();
    }

    public Distribuidora requerir(String codigo) {
        var d = distribuidoras.get(codigo);
        if (d == null) {
            throw new DistribuidoraDesconocidaException(codigo);
        }
        return d;
    }

    /**
     * @param host    base del gateway de comandos, ej. https://dyssa.gescom.online
     * @param realm   realm de Keycloak, ej. gcw-dyssa
     * @param usuario usuario de API de la distribuidora
     * @param clave   clave de ese usuario
     */
    public record Distribuidora(Erp erp, String host, String realm, String usuario, String clave) {}

    public static class DistribuidoraDesconocidaException extends RuntimeException {
        public DistribuidoraDesconocidaException(String codigo) {
            super("Distribuidora no configurada: " + codigo);
        }
    }
}
