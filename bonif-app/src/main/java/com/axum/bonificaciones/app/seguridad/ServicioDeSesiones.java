package com.axum.bonificaciones.app.seguridad;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Las sesiones del panel.
 *
 * Token opaco en memoria, no JWT: son pocas personas, la sesion tiene que poder **revocarse al
 * instante** (alguien se va del equipo) y no quiero administrar claves de firma para eso. El
 * precio es que al reiniciar el servicio hay que volver a loguearse, que para un panel es
 * aceptable.
 */
@Service
@ConditionalOnProperty(name = "bonificaciones.distribuidoras-en-base", havingValue = "true",
        matchIfMissing = true)
public class ServicioDeSesiones {

    private static final Duration DURACION = Duration.ofHours(8);

    private final RepositorioDeUsuarios usuarios;
    private final Cache<String, String> sesiones =
            Caffeine.newBuilder().expireAfterAccess(DURACION).build();
    private final SecureRandom random = new SecureRandom();

    ServicioDeSesiones(RepositorioDeUsuarios usuarios) {
        this.usuarios = usuarios;
    }

    /** @return el token, o vacio si usuario o clave no son correctos */
    public Optional<String> ingresar(String usuario, String clave) {
        return usuarios.autenticar(usuario, clave).map(u -> {
            var bytes = new byte[32];
            random.nextBytes(bytes);
            var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            sesiones.put(token, u.usuario());
            return token;
        });
    }

    public Optional<String> usuarioDe(String token) {
        return token == null ? Optional.empty() : Optional.ofNullable(sesiones.getIfPresent(token));
    }

    public void salir(String token) {
        if (token != null) sesiones.invalidate(token);
    }

    public long duracionEnMinutos() {
        return DURACION.toMinutes();
    }
}
