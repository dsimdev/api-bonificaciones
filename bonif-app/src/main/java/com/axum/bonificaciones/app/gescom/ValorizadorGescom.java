package com.axum.bonificaciones.app.gescom;

import com.axum.bonificaciones.app.config.Distribuidoras;
import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.axum.bonificaciones.core.model.BonificacionAplicada;
import com.axum.bonificaciones.core.model.CalculadoPor;
import com.axum.bonificaciones.core.model.Condicion;
import com.axum.bonificaciones.core.model.Criterio;
import com.axum.bonificaciones.core.model.Fuente;
import com.axum.bonificaciones.core.model.ItemAValorizar;
import com.axum.bonificaciones.core.model.LineaValorizada;
import com.axum.bonificaciones.core.model.Operacion;
import com.axum.bonificaciones.core.model.PedidoAValorizar;
import com.axum.bonificaciones.core.model.Supuesto;
import com.axum.bonificaciones.core.model.Valorizacion;
import com.axum.bonificaciones.core.puerto.Valorizador;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Valoriza delegando en eval-pedido.
 *
 * GESCOM tiene motor, asi que el numero lo da el ERP y aca NO se reimplementa la evaluacion de
 * criterios: si nuestro numero se desviara del suyo, el preventista veria un precio y el pedido
 * entraria con otro. Lo unico que agregamos es el "por que", cruzando con el catalogo.
 */
@Service
public class ValorizadorGescom implements Valorizador {

    private static final Logger log = LoggerFactory.getLogger(ValorizadorGescom.class);

    private final ClienteGescom cliente;
    private final CatalogoGescom catalogo;
    private final Distribuidoras distribuidoras;
    private final Clock reloj;

    ValorizadorGescom(ClienteGescom cliente, CatalogoGescom catalogo,
                      Distribuidoras distribuidoras, Clock reloj) {
        this.cliente = cliente;
        this.catalogo = catalogo;
        this.distribuidoras = distribuidoras;
        this.reloj = reloj;
    }

    @Override
    public Valorizacion valorizar(String tenant, PedidoAValorizar pedido) {
        var d = diagnosticar(tenant, pedido);

        var noCierran = d.valorizacion().lineasQueNoCierran();
        if (!noCierran.isEmpty()) {
            // No se devuelve un numero que no cierra: en un checkout eso termina en una factura
            // mal. Falla explicito, no ajusta en silencio. El diagnostico del panel SI lo
            // devuelve, con el crudo, que es la unica forma de ver por que no cerro.
            log.error("eval-pedido devolvio {} linea(s) incoherentes para el tenant {}: {}",
                    noCierran.size(), tenant,
                    noCierran.stream().map(LineaValorizada::codigoItem).toList());
            throw new ErrorDeGateway(CodigoDeError.RESPUESTA_INCOHERENTE,
                    "eval-pedido devolvio lineas donde el neto con descuento no se corresponde "
                            + "con el neto y el descuento");
        }
        return d.valorizacion();
    }

    /**
     * Lo mismo que {@link #valorizar}, pero conservando lo que se le mando al ERP y lo que
     * contesto crudo, y <b>sin voltear</b> cuando las lineas no cierran.
     *
     * Es para el panel. Cuando una tienda reclama un descuento hay tres sospechosos -- el ERP,
     * nuestra normalizacion y lo que muestra la tienda -- y la unica forma de saber cual es poner
     * el crudo del ERP al lado de lo que devolvemos nosotros. Que no tire en el caso incoherente
     * es a proposito: ese es justo el caso que hay que poder mirar.
     */
    public Diagnostico diagnosticar(String tenant, PedidoAValorizar pedido) {
        var gescom = distribuidoras.requerir(tenant).gescom();
        if (gescom == null) {
            throw new ErrorDeGateway(CodigoDeError.FUENTE_NO_CONFIGURADA,
                    "La distribuidora " + tenant + " no tiene configurado GESCOM");
        }

        var items = pedido.items().stream()
                .map(i -> new DtosGescom.ItemDePedido(i.codigo(), i.cantidad(), i.unidad(),
                        i.unidadFactor(), pedido.codigoListaPrecio(), i.precioUnitario()))
                .toList();

        // El GUID lo generamos nosotros, uno por llamada: asi un reintento no depende de que el
        // consumidor se acuerde de mandarlo (la leccion del operationGuid de Axum).
        var sobre = new DtosGescom.SobreDePedido(new DtosGescom.Pedido(
                UUID.randomUUID().toString(), pedido.codigoCliente(), items));

        var respuesta = cliente.postConCrudo(tenant, gescom, "ventas", "eval-pedido", sobre,
                DtosGescom.VentaEvaluada[].class);

        var valorizacion = new Valorizacion(Fuente.GESCOM, tenant, CalculadoPor.ERP,
                OffsetDateTime.now(reloj), supuestos(pedido),
                lineas(tenant, respuesta.cuerpo()));

        return new Diagnostico(valorizacion, respuesta.enviado(), respuesta.crudo(),
                comparar(respuesta.cuerpo(), valorizacion));
    }

    /**
     * Qué resolvimos nosotros porque el pedido no lo traía. Nunca un default silencioso.
     *
     * Un ítem se valoriza con su {@code precioUnitario} si lo trae, y si no con la lista del
     * pedido. Lo que hay que avisar son los dos bordes: los ítems que no tienen ni lo uno ni lo
     * otro (el ERP les pone la lista del cliente, que puede no ser la que vio quien compra), y
     * que cuando vienen los dos gana el precio.
     *
     * El carrito mixto —algunos ítems con precio, otros sin— es válido a propósito: el día que la
     * tienda tenga un artículo sin precio cargado, rechazar el carrito entero sería inventar una
     * regla que el ERP no tiene.
     */
    private List<Supuesto> supuestos(PedidoAValorizar pedido) {
        var supuestos = new ArrayList<Supuesto>();
        var hayLista = pedido.codigoListaPrecio() != null && !pedido.codigoListaPrecio().isBlank();

        var sinPrecio = pedido.items().stream()
                .filter(i -> !i.traePrecio())
                .map(ItemAValorizar::codigo)
                .toList();

        if (!hayLista && !sinPrecio.isEmpty()) {
            supuestos.add(Supuesto.sinPrecioNiLista(sinPrecio));
        }
        if (hayLista && sinPrecio.size() < pedido.items().size()) {
            supuestos.add(Supuesto.precioYListaJuntos());
        }
        return supuestos;
    }

    /**
     * @param pedidoEnviado  el cuerpo exacto que se le mando a eval-pedido, con los nombres de
     *                       campo de GESCOM. Sirve para pegarlo en Postman tal cual
     * @param respuestaCruda lo que contesto GESCOM, sin normalizar. El descuento aca viene como
     *                       FRACCION (0.1); el nuestro, como porcentaje (10)
     * @param comparacion    linea por linea, el numero del ERP contra el que exponemos
     */
    public record Diagnostico(Valorizacion valorizacion, String pedidoEnviado,
                              String respuestaCruda, List<Comparacion> comparacion) {}

    /**
     * Lo que dijo el ERP al lado de lo que exponemos, para un item.
     *
     * @param descuentoEnElErp    FRACCION, como lo da GESCOM (0.1)
     * @param descuentoQueDevolvemos PORCENTAJE, como lo expone el contrato (10)
     * @param coincide            false = el bug es NUESTRO, de la normalizacion. Es la unica
     *                            manera de distinguirlo de un numero que ya venia mal del ERP
     */
    public record Comparacion(String codigoItem,
                              BigDecimal descuentoEnElErp,
                              BigDecimal descuentoQueDevolvemos,
                              BigDecimal netoEnElErp,
                              BigDecimal netoQueDevolvemos,
                              BigDecimal netoConDescuentoEnElErp,
                              BigDecimal netoConDescuentoQueDevolvemos,
                              boolean coincide) {}

    /**
     * Cruza la respuesta cruda del ERP con lo que quedo en nuestro modelo.
     *
     * Vive en el conector y no en el controller del panel porque hay que conocer los nombres de
     * campo de GESCOM para hacerlo, y esos no salen de este paquete.
     *
     * Se cruza por POSICION, no por codigo de item: eval-pedido puede devolver dos lineas del
     * mismo item (la que se pidio y la que regalo una promo), asi que buscar por codigo
     * compararia la linea equivocada contra la otra.
     */
    private List<Comparacion> comparar(DtosGescom.VentaEvaluada[] ventas, Valorizacion nuestra) {
        if (ventas == null) return List.of();
        var delErp = new ArrayList<DtosGescom.ItemEvaluado>();
        for (var venta : ventas) {
            if (venta.items() != null) delErp.addAll(venta.items());
        }

        var comparaciones = new ArrayList<Comparacion>();
        for (int i = 0; i < delErp.size() && i < nuestra.lineas().size(); i++) {
            var erp = delErp.get(i);
            var linea = nuestra.lineas().get(i);
            var esperado = aPorcentaje(erp.descuentoTotal());
            comparaciones.add(new Comparacion(
                    erp.itemCodigo(),
                    erp.descuentoTotal(),
                    linea.descuento(),
                    erp.precioNetoTotal(),
                    linea.neto(),
                    erp.precioNetoTotalConDesc(),
                    linea.netoConDescuento(),
                    esperado.compareTo(linea.descuento()) == 0
                            && iguales(erp.precioNetoTotal(), linea.neto())
                            && iguales(erp.precioNetoTotalConDesc(), linea.netoConDescuento())));
        }
        return comparaciones;
    }

    private boolean iguales(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == b;
        return a.compareTo(b) == 0;
    }

    private List<LineaValorizada> lineas(String tenant, DtosGescom.VentaEvaluada[] ventas) {
        if (ventas == null) return List.of();
        var porId = criteriosPorId(tenant);
        var lineas = new ArrayList<LineaValorizada>();
        for (var venta : ventas) {
            if (venta.items() == null) continue;
            for (var item : venta.items()) {
                lineas.add(new LineaValorizada(
                        item.itemCodigo(),
                        item.cantidad(),
                        item.precioNetoTotal(),
                        aPorcentaje(item.descuentoTotal()),
                        item.precioNetoTotalConDesc(),
                        Boolean.TRUE.equals(item.creadoPorPromo()),
                        bonificaciones(item, porId)));
            }
        }
        return lineas;
    }

    private List<BonificacionAplicada> bonificaciones(DtosGescom.ItemEvaluado item,
                                                      Map<String, Criterio> porId) {
        if (item.detalleDescuento() == null) return List.of();
        var bonificaciones = new ArrayList<BonificacionAplicada>();
        for (var detalle : item.detalleDescuento()) {
            var id = detalle.promoId() == null ? null : String.valueOf(detalle.promoId());
            var porcentaje = aPorcentaje(detalle.descuento());
            bonificaciones.add(new BonificacionAplicada(
                    id, detalle.promoNombre(), porcentaje,
                    condicionesQueExplican(porId.get(id), porcentaje)));
        }
        return bonificaciones;
    }

    /**
     * Por que cayo este descuento.
     *
     * Un criterio puede tener varios modificadores con descuentos distintos apuntando a
     * condiciones distintas -- asi se arma el "10% en global y 5% en Pehuamar" del criterio 2 de
     * dyssa. eval-pedido nos dice QUE porcentaje aplico, asi que se busca el modificador que lo
     * produjo y se devuelven las condiciones a las que ESE modificador apunta, no todas las del
     * criterio.
     *
     * Y se usan las condiciones en juego, no la lista completa: el catalogo real tiene condiciones
     * huerfanas que no participan de la evaluacion (ver Criterio.condicionesEnJuego).
     */
    private List<Condicion> condicionesQueExplican(Criterio criterio, BigDecimal porcentaje) {
        if (criterio == null) return List.of();
        return criterio.modificadores().stream()
                .filter(m -> m.operacion() instanceof Operacion.Descuento d
                        && d.descuento() != null
                        && d.descuento().compareTo(porcentaje) == 0)
                .findFirst()
                .map(criterio::condicionesDe)
                .orElseGet(criterio::condicionesHoja);
    }

    /**
     * El catalogo es para explicar, no para calcular: si falla, la valorizacion igual sale -- con
     * el numero del ERP y sin el detalle de condiciones. Voltear un checkout porque no pudimos
     * traer el "por que" seria cambiar un lujo por la venta.
     */
    private Map<String, Criterio> criteriosPorId(String tenant) {
        try {
            return catalogo.criterios(tenant).stream()
                    .filter(c -> c.id() != null)
                    .collect(Collectors.toMap(Criterio::id, Function.identity(), (a, b) -> a));
        } catch (RuntimeException e) {
            log.warn("No se pudo traer el catalogo de {} para enriquecer la valorizacion: {}",
                    tenant, e.getMessage());
            return Map.of();
        }
    }

    /** GESCOM da fraccion (0.1); el contrato expone porcentaje (10). */
    private BigDecimal aPorcentaje(BigDecimal fraccion) {
        return fraccion == null ? BigDecimal.ZERO : fraccion.multiply(MapeadorGescom.A_PORCENTAJE);
    }
}
