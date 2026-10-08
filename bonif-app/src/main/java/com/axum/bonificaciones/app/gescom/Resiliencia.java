package com.axum.bonificaciones.app.gescom;

import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Qué hacer cuando GESCOM falla: reintentar una vez lo que valga la pena, y dejar de insistir con
 * una distribuidora que está caída.
 *
 * <b>Reintentar acá es seguro, y eso no es obvio.</b> En general reintentar un POST es riesgoso
 * porque puede duplicar algo; en este gateway no: {@code eval-pedido} es dry-run y no persiste
 * nada, y el identificador del pedido lo generamos nosotros por llamada. El gateway es de solo
 * lectura, así que el peor caso de un reintento es gastar otra llamada.
 *
 * <b>Un solo reintento, no tres.</b> Esto se llama en un checkout, con alguien esperando: dos
 * intentos de 15 segundos ya son 30 y la venta se perdió igual. El reintento está para el fallo
 * de red de una vez, no para esperar a que un ERP caído se levante.
 *
 * <b>El cortacircuito es por distribuidora.</b> Con ~1000 tenants, que una esté caída no puede
 * costarle el timeout completo a las demás ni a cada checkout de esa misma. Después de unos
 * fallos seguidos se falla rápido por un rato, con el mismo código de error que habría devuelto
 * el timeout — el consumidor no tiene que distinguir los dos casos, para él es "la fuente no está
 * disponible" en los dos.
 *
 * <b>Lo que se cuenta son INTENTOS, no pedidos.</b> Una valorización que falla gasta dos intentos
 * (el original y el reintento), así que con el default de 4 el circuito se abre a la segunda
 * valorización fallida seguida, no a la cuarta. El nombre de la propiedad lo dice para que nadie
 * calcule mal el umbral; antes se llamaba {@code fallos-para-abrir} y engañaba.
 */
@Component
public class Resiliencia {

    private static final Logger log = LoggerFactory.getLogger(Resiliencia.class);

    private final int intentosFallidosParaAbrir;
    private final Duration esperaAntesDeReintentar;
    private final Cache<String, AtomicInteger> fallosSeguidos;

    Resiliencia(
            @Value("${bonificaciones.gescom.intentos-fallidos-para-abrir:4}") int intentosFallidosParaAbrir,
            @Value("${bonificaciones.gescom.espera-reintento-ms:200}") long esperaReintentoMs,
            @Value("${bonificaciones.gescom.cortacircuito-segundos:30}") long cortacircuitoSegundos) {
        this.intentosFallidosParaAbrir = intentosFallidosParaAbrir;
        this.esperaAntesDeReintentar = Duration.ofMillis(esperaReintentoMs);
        // Expira por escritura: pasada la ventana, el contador arranca de cero y la próxima
        // llamada vuelve a intentar de verdad. No hace falta un temporizador que lo cierre.
        this.fallosSeguidos = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(cortacircuitoSegundos))
                .build();
    }

    /**
     * @param tenant null para las llamadas que no son de una distribuidora guardada (verificar
     *               credenciales en un alta). Ahí no hay cortacircuito a propósito: quien está
     *               dando de alta necesita el intento real, no un "está caída" de hace 20
     *               segundos que además sería de otra credencial.
     */
    public <T> T conReintento(String tenant, String que, Supplier<T> llamada) {
        verificarCircuito(tenant, que);
        try {
            var resultado = llamada.get();
            exito(tenant);
            return resultado;
        } catch (ErrorDeGateway e) {
            if (!esTransitorio(e)) {
                // Un pedido mal armado o un cliente que no existe no son un fallo de la fuente:
                // no cuentan para el cortacircuito ni se reintentan. Si contaran, una tienda con
                // un bug de integración dejaría a su distribuidora marcada como caída.
                throw e;
            }
            registrarFallo(tenant);
            return reintentar(tenant, que, llamada, e);
        }
    }

    private <T> T reintentar(String tenant, String que, Supplier<T> llamada, ErrorDeGateway primero) {
        log.warn("GESCOM falló en {} para {}: {}. Reintentando una vez.",
                que, tenant == null ? "(credencial sin guardar)" : tenant, primero.codigo());
        esperar();
        try {
            var resultado = llamada.get();
            exito(tenant);
            return resultado;
        } catch (ErrorDeGateway segundo) {
            if (esTransitorio(segundo)) registrarFallo(tenant);
            // Se propaga el error del SEGUNDO intento: es el más reciente y su crudo es el que
            // describe el estado actual de la fuente.
            throw segundo;
        }
    }

    /**
     * Transitorio = tiene sentido volver a intentar lo mismo.
     *
     * {@code FUENTE_ERROR_DESCONOCIDO} queda afuera adrede: es el genérico con el que GESCOM tapa
     * sus errores de runtime, y reintentar el mismo payload lo más probable es que dé el mismo
     * error. Al consumidor se le dice que puede reintentar una vez con cuidado, y esa decisión es
     * suya, no nuestra dentro de un checkout.
     */
    private boolean esTransitorio(ErrorDeGateway e) {
        return e.codigo() == CodigoDeError.FUENTE_NO_DISPONIBLE;
    }

    private void verificarCircuito(String tenant, String que) {
        if (tenant == null) return;
        var cuenta = fallosSeguidos.getIfPresent(tenant);
        if (cuenta != null && cuenta.get() >= intentosFallidosParaAbrir) {
            log.warn("Cortacircuito abierto para {}: {} fallos seguidos. No se llama a {}.",
                    tenant, cuenta.get(), que);
            throw new ErrorDeGateway(CodigoDeError.FUENTE_NO_DISPONIBLE,
                    "GESCOM no está respondiendo para la distribuidora " + tenant
                            + ". Se dejó de insistir por unos segundos; probá de nuevo en un rato.");
        }
    }

    private void registrarFallo(String tenant) {
        if (tenant == null) return;
        fallosSeguidos.get(tenant, t -> new AtomicInteger()).incrementAndGet();
    }

    private void exito(String tenant) {
        if (tenant != null) fallosSeguidos.invalidate(tenant);
    }

    /**
     * Olvida los fallos acumulados de un tenant.
     *
     * Para los tests: el bean vive en el contexto de Spring, que es el mismo para toda la clase,
     * asi que sin esto un test arranca con el circuito ya abierto por el anterior. Mismo motivo
     * que el olvidar() del catalogo y del servicio de token.
     */
    public void olvidar(String tenant) {
        fallosSeguidos.invalidate(tenant);
    }

    private void esperar() {
        if (esperaAntesDeReintentar.isZero() || esperaAntesDeReintentar.isNegative()) return;
        try {
            Thread.sleep(esperaAntesDeReintentar.toMillis());
        } catch (InterruptedException e) {
            // Si alguien canceló el pedido, no se sigue insistiendo con la fuente.
            Thread.currentThread().interrupt();
            throw new ErrorDeGateway(CodigoDeError.FUENTE_NO_DISPONIBLE,
                    "La consulta a GESCOM se interrumpió antes del reintento");
        }
    }
}
