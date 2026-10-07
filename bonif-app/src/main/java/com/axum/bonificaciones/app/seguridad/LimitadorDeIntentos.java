package com.axum.bonificaciones.app.seguridad;

import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Frena la fuerza bruta contra las api-keys, contando por tenant.
 *
 * Cuenta solo los intentos FALLIDOS: el objetivo es que nadie pruebe claves de a millones, no
 * limitar el trafico legitimo (para eso esta la cuota, que es otra cosa).
 *
 * Por tenant y no por IP: una distribuidora con varias cajas comparte una sola clave, y quien
 * prueba claves puede cambiar de IP. La contra es que alguien podria bloquear a una distribuidora
 * a proposito -- por eso el bloqueo es corto y no permanente.
 */
@Component
public class LimitadorDeIntentos {

    private static final int MAXIMO = 10;
    private static final Duration VENTANA = Duration.ofMinutes(5);

    private final Cache<String, AtomicInteger> fallidos =
            Caffeine.newBuilder().expireAfterWrite(VENTANA).build();

    public void verificarNoBloqueado(String tenant) {
        var cuenta = fallidos.getIfPresent(tenant);
        if (cuenta != null && cuenta.get() >= MAXIMO) {
            throw new ErrorDeGateway(CodigoDeError.DEMASIADOS_INTENTOS,
                    "Demasiados intentos fallidos para " + tenant
                            + ". Espera unos minutos.");
        }
    }

    public void registrarFallo(String tenant) {
        if (tenant == null) return;
        fallidos.get(tenant, t -> new AtomicInteger()).incrementAndGet();
    }

    public void registrarExito(String tenant) {
        if (tenant != null) fallidos.invalidate(tenant);
    }
}
