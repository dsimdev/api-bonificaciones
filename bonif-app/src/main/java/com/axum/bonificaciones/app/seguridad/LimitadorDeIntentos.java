package com.axum.bonificaciones.app.seguridad;

import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Frena la fuerza bruta contra las api-keys, contando los intentos FALLIDOS por tenant.
 *
 * **Solo frena a quien falla, nunca a una clave valida.** La primera version frenaba al tenant
 * entero antes de mirar la clave: con diez pedidos con claves inventadas en cinco minutos,
 * cualquiera dejaba sin descuentos el checkout de una distribuidora. Ahora el interceptor valida la
 * clave primero y solo consulta esto cuando la clave NO sirve: pasado el limite, esos intentos
 * reciben 429 en vez de 401, y la tienda con su clave buena no se entera.
 *
 * Por tenant y no por IP: detras de IIS todos los pedidos llegan desde 127.0.0.1, y quien prueba
 * claves puede cambiar de IP. Un exito NO reinicia la cuenta: si lo hiciera, el trafico normal de
 * la tienda le borraria los fallos a quien esta probando claves.
 */
@Component
public class LimitadorDeIntentos {

    private static final int MAXIMO = 10;
    private static final Duration VENTANA = Duration.ofMinutes(5);

    private final Cache<String, AtomicInteger> fallidos =
            Caffeine.newBuilder().expireAfterWrite(VENTANA).build();

    /**
     * Registra un intento fallido. Si el tenant ya paso el limite, tira DEMASIADOS_INTENTOS en vez
     * de dejar que se informe el 401.
     */
    public void registrarFallo(String tenant) {
        if (tenant == null) return;
        var cuenta = fallidos.get(tenant, t -> new AtomicInteger());
        if (cuenta.incrementAndGet() > MAXIMO) {
            throw new ErrorDeGateway(CodigoDeError.DEMASIADOS_INTENTOS,
                    "Demasiados intentos con claves invalidas para " + tenant
                            + ". Espera unos minutos.");
        }
    }

    /** Para los tests: el limitador es un singleton y la cuenta sobrevive entre tests. */
    public void olvidar(String tenant) {
        fallidos.invalidate(tenant);
    }
}
