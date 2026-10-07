package com.axum.bonificaciones.app.seguridad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Crea el primer usuario del panel si no hay ninguno.
 *
 * Es el problema del huevo y la gallina: los usuarios se crean desde el panel, pero al panel se
 * entra con un usuario. Sin esto, una instalacion nueva no tiene por donde empezar.
 *
 * Solo corre si la tabla esta VACIA: no es una forma de resetear contrasenias ni de dejar una
 * puerta de servicio abierta. Y avisa fuerte por log que hay que cambiarla.
 */
@Configuration
@ConditionalOnProperty(name = "bonificaciones.distribuidoras-en-base", havingValue = "true",
        matchIfMissing = true)
public class UsuarioInicial {

    private static final Logger log = LoggerFactory.getLogger(UsuarioInicial.class);

    @Bean
    ApplicationRunner crearUsuarioInicial(
            RepositorioDeUsuarios usuarios,
            @Value("${bonificaciones.usuario-inicial:}") String usuario,
            @Value("${bonificaciones.clave-inicial:}") String clave) {

        return args -> {
            if (usuarios.hayAlguno()) return;

            if (usuario.isBlank() || clave.isBlank()) {
                log.warn("No hay usuarios del panel y no se configuro uno inicial. "
                        + "Nadie puede administrar: defini USUARIO_INICIAL y CLAVE_INICIAL "
                        + "y volve a arrancar.");
                return;
            }

            usuarios.crear(usuario, clave, "Usuario inicial", "arranque");
            log.warn("Se creo el usuario inicial '{}' porque no habia ninguno. "
                    + "CAMBIALE LA CONTRASENIA y sacá CLAVE_INICIAL del entorno.", usuario);
        };
    }
}
