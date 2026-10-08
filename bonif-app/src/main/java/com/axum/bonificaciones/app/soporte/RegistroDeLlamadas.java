package com.axum.bonificaciones.app.soporte;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lo que hace falta para contestar "la tienda dice que no funciona".
 *
 * Son dos cosas distintas y las dos hacen falta:
 *
 * <ul>
 *   <li><b>El log</b> es el registro durable. Una línea por llamada, con la distribuidora, qué se
 *       pidió, cómo salió y cuánto tardó. WinSW rota los logs por día en el servidor, así que eso
 *       contesta "¿qué pasó ayer a las tres?", que es la pregunta que llega por teléfono.</li>
 *   <li><b>Los contadores</b> son el estado de ahora, para el panel: cuántas llamadas y cuántas
 *       fallaron por distribuidora, y con qué código. Viven en memoria y se reinician con el
 *       servicio -- a propósito: persistirlos sería una tabla de auditoría, y eso es una decisión
 *       aparte (este gateway es de solo lectura y no guarda pedidos).</li>
 * </ul>
 *
 * <b>Acá no entra ni un secreto.</b> Nunca la api-key, nunca el usuario ni la clave de GESCOM, y
 * tampoco el cuerpo del pedido. Lo que se registra es de qué distribuidora, qué operación, el
 * código de error y el tiempo: alcanza para diagnosticar y no convierte el log en un lugar del
 * que haya que cuidarse.
 */
@Component
public class RegistroDeLlamadas {

    private static final Logger log = LoggerFactory.getLogger(RegistroDeLlamadas.class);

    private final Map<String, Contadores> porTenant = new ConcurrentHashMap<>();

    /**
     * @param codigoDeError null = salió bien
     * @param ms            cuánto tardó la llamada completa, incluido el ERP
     */
    public void registrar(String tenant, String operacion, String codigoDeError, long ms) {
        var clave = tenant == null ? "(sin tenant)" : tenant;
        var contadores = porTenant.computeIfAbsent(clave, t -> new Contadores());
        contadores.total.incrementAndGet();
        contadores.milisegundos.addAndGet(ms);

        if (codigoDeError == null) {
            // INFO y no DEBUG: una valorización por checkout no es volumen de log, y cuando una
            // tienda reclama lo primero que se quiere ver es si la llamada llegó.
            log.info("{} {} ok en {}ms", clave, operacion, ms);
            return;
        }
        contadores.fallidas.incrementAndGet();
        contadores.porCodigo.computeIfAbsent(codigoDeError, c -> new AtomicLong())
                .incrementAndGet();
        log.warn("{} {} fallo con {} en {}ms", clave, operacion, codigoDeError, ms);
    }

    /**
     * Borra los contadores.
     *
     * Para los tests: el bean vive en el contexto de Spring, que es el mismo para toda la clase,
     * así que sin esto un test cuenta las llamadas del anterior. Mismo motivo que el olvidar() del
     * catálogo, del token y de la resiliencia.
     */
    public void olvidarTodo() {
        porTenant.clear();
    }

    /** Ordenado por fallidas desc: lo primero que se quiere ver es qué distribuidora está mal. */
    public List<Actividad> actividad() {
        return porTenant.entrySet().stream()
                .map(e -> e.getValue().a(e.getKey()))
                .sorted(Comparator.comparingLong(Actividad::fallidas).reversed()
                        .thenComparing(Actividad::tenant))
                .toList();
    }

    /**
     * @param msPromedio redondeado. Sirve para "¿está lento?", no para un percentil
     * @param porCodigo  cuántas veces salió cada código de error. Vacío = ninguna falló
     */
    public record Actividad(String tenant, long total, long fallidas, long msPromedio,
                            Map<String, Long> porCodigo) {}

    private static final class Contadores {
        private final AtomicLong total = new AtomicLong();
        private final AtomicLong fallidas = new AtomicLong();
        private final AtomicLong milisegundos = new AtomicLong();
        private final Map<String, AtomicLong> porCodigo = new ConcurrentHashMap<>();

        Actividad a(String tenant) {
            long t = total.get();
            return new Actividad(tenant, t, fallidas.get(),
                    t == 0 ? 0 : milisegundos.get() / t,
                    porCodigo.entrySet().stream().collect(
                            java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                    e -> e.getValue().get())));
        }
    }
}
