package com.axum.bonificaciones.app.web;

import com.axum.bonificaciones.core.model.BonificacionAplicada;
import com.axum.bonificaciones.core.model.CalculadoPor;
import com.axum.bonificaciones.core.model.Condicion;
import com.axum.bonificaciones.core.model.Criterio;
import com.axum.bonificaciones.core.model.Fuente;
import com.axum.bonificaciones.core.model.ItemAValorizar;
import com.axum.bonificaciones.core.model.LineaValorizada;
import com.axum.bonificaciones.core.model.Modificador;
import com.axum.bonificaciones.core.model.Operacion;
import com.axum.bonificaciones.core.model.PedidoAValorizar;
import com.axum.bonificaciones.core.model.Valorizacion;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * El contrato publico. Es propio: no asoma nada de GESCOM ni de Axum.
 *
 * Las validaciones estan aca para que un pedido invalido falle ANTES de gastar una llamada a la
 * fuente -- cada una cuesta un token y tiempo de checkout.
 */
public final class Dtos {

    private Dtos() {}

    /**
     * @param listaPrecio fuertemente recomendado: la lista CAMBIA EL PRECIO. Si no viene, el ERP
     *                    usa la del cliente y la respuesta lo avisa en `supuestos`
     * @param referencia  identificador propio de la tienda (el carrito, el pedido). Se devuelve
     *                    tal cual, para poder rastrear despues que le respondimos a quien
     */
    public record PedidoRequest(
            @NotBlank String cliente,
            String listaPrecio,
            String referencia,
            @NotEmpty @Valid List<ItemRequest> items) {}

    /**
     * @param precioUnitario precio POR UNIDAD, no total de la linea. Opcional: si viene, el ERP
     *                       valoriza con el; si no, usa la lista. Verificado en vivo contra dyssa
     *                       el 2026-10-08: el ERP sigue aplicando los mismos criterios y sigue
     *                       siendo el que calcula, asi que `calculadoPor` no deja de ser ERP
     */
    public record ItemRequest(
            @NotBlank String codigo,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal cantidad,
            String unidad,
            BigDecimal unidadFactor,
            @DecimalMin(value = "0", inclusive = false,
                    message = "si lo mandas, tiene que ser mayor que cero")
            BigDecimal precioUnitario) {

        /** GESCOM exige la unidad; "Unidad" es el valor por defecto verificado contra la API. */
        public String unidadODefecto() {
            return unidad == null || unidad.isBlank() ? "Unidad" : unidad;
        }

        public BigDecimal factorODefecto() {
            return unidadFactor == null ? BigDecimal.ONE : unidadFactor;
        }
    }

    /**
     * @param calculadoPor ERP o GATEWAY. Parte del contrato, no debug: "lo dijo el ERP" y "lo
     *                     calculamos nosotros" no valen lo mismo frente a un reclamo
     */
    public record ValorizacionResponse(
            Fuente fuente,
            String tenant,
            CalculadoPor calculadoPor,
            OffsetDateTime consultadoEn,
            String referencia,
            List<SupuestoResponse> supuestos,
            TotalesResponse totales,
            List<LineaResponse> lineas) {}

    /** Algo que resolvimos nosotros porque el pedido no lo traia. Vacio = no hubo ninguno. */
    public record SupuestoResponse(String codigo, String mensaje) {}

    /** @param descuento el ahorro en PESOS, no un porcentaje. */
    public record TotalesResponse(
            BigDecimal neto, BigDecimal descuento, BigDecimal netoConDescuento) {}

    /** @param descuento PORCENTAJE: 10 = 10%. */
    /**
     * @param creadaPorPromo true cuando la linea NO la pidio el cliente: la agrego una
     *                       bonificacion (los "5+1 sin cargo" y los combos de GESCOM)
     */
    public record LineaResponse(
            String codigo,
            BigDecimal cantidad,
            BigDecimal neto,
            BigDecimal descuento,
            BigDecimal netoConDescuento,
            boolean creadaPorPromo,
            List<BonificacionResponse> bonificaciones) {}

    /**
     * @param descuento PORCENTAJE: 10 = 10%
     * @param condiciones por que aplico, cuando se pudo cruzar con el catalogo. Vacio no significa
     *                    "sin condiciones": significa que no se pudo enriquecer
     */
    public record BonificacionResponse(
            String id,
            String nombre,
            BigDecimal descuento,
            List<CondicionResponse> condiciones) {}

    /**
     * @param descripcion el texto que el propio ERP le pone a la condicion ("La venta tiene items
     *                    de una o mas marcas"). Se pasa tal cual: explica mejor que cualquier
     *                    cosa que redactemos nosotros, y viene del mismo lugar que el numero
     */
    public record CondicionResponse(
            String tipo,
            String descripcion,
            List<String> valores,
            boolean invertida,
            Integer cantidadMinima) {}

    // --- GET /v1/{tenant}/criterios

    /**
     * @param actualizadoEn cuando se trajeron los criterios de GESCOM. Si es anterior a
     *                      {@code consultadoEn} es porque se sirvieron del cache
     */
    public record CriteriosResponse(
            Fuente fuente,
            String tenant,
            OffsetDateTime consultadoEn,
            OffsetDateTime actualizadoEn,
            int total,
            List<CriterioResponse> criterios) {}

    /**
     * @param condiciones solo las que estan EN JUEGO: se camina el arbol desde la condicion raiz.
     *                    El ERP devuelve condiciones huerfanas que no participan de la evaluacion
     */
    /**
     * @param aplicaATodo true si el criterio no tiene condiciones de articulo: vale para todo el
     *                    catalogo. false si tiene condiciones de articulo: los codigos estan en
     *                    {@code articulos} (que puede estar vacio si no se pudo resolver, ej.
     *                    CALIBRE_ARTICULO o combos)
     * @param articulos   los codigos de articulo a los que aplica, ya resueltos cruzando las
     *                    condiciones de articulo con el catalogo de GESCOM. Sorted para determinismo
     */
    public record CriterioResponse(
            String id,
            String nombre,
            String descripcion,
            boolean activo,
            LocalDate vigenteDesde,
            LocalDate vigenteHasta,
            List<String> clientes,
            List<CondicionResponse> condiciones,
            List<ModificadorResponse> bonificaciones,
            boolean aplicaATodo,
            List<String> articulos) {}

    /**
     * Que hace la bonificacion. `tipo` discrimina que campos vienen cargados.
     *
     * @param tipo        DESCUENTO | ESCALA | ITEM_SIN_CARGO | NO_RECONOCIDO
     * @param descuento   PORCENTAJE (10 = 10%). Solo en DESCUENTO
     * @param tramos      solo en ESCALA, ordenados por cantidad ascendente
     * @param tipoEnElErp solo en NO_RECONOCIDO: el nombre que le da GESCOM, para que se vea que
     *                    apareci algo que no sabemos interpretar
     * @param aplicaA     a que condiciones apunta. Vacio en el ERP = cae sobre todo lo que califique
     */
    public record ModificadorResponse(
            String tipo,
            String descripcion,
            BigDecimal descuento,
            BigDecimal tope,
            List<TramoResponse> tramos,
            String codigoItem,
            BigDecimal cantidad,
            String tipoEnElErp,
            String crudo,
            List<CondicionResponse> aplicaA) {}

    /** @param descuento PORCENTAJE (10 = 10%) a partir de esa cantidad. */
    public record TramoResponse(BigDecimal desdeCantidad, BigDecimal descuento) {}

    public record ErrorResponse(String codigo, String mensaje, String crudo) {}

    // --- Del modelo normalizado al contrato
    //
    // Vive aca y no en el controller porque hay dos que la necesitan: /v1/{tenant}/valorizaciones
    // y el diagnostico del panel. El panel tiene que ver EXACTAMENTE lo que ve la tienda, asi que
    // los dos tienen que armar la respuesta con el mismo codigo, no con dos copias que se
    // despeguen.

    /**
     * El pedido del contrato publico al del modelo. Lo usa tambien el diagnostico del panel: lo
     * que se prueba desde ahi tiene que recorrer el mismo camino que lo que manda la tienda, o
     * deja de servir para decidir de quien es el problema.
     */
    public static PedidoAValorizar pedidoDe(PedidoRequest pedido) {
        var items = pedido.items().stream()
                .map(i -> new ItemAValorizar(i.codigo(), i.cantidad(), i.unidadODefecto(),
                        i.factorODefecto(), i.precioUnitario()))
                .toList();
        return new PedidoAValorizar(pedido.cliente(), pedido.listaPrecio(), items);
    }

    public static ValorizacionResponse respuestaDe(Valorizacion v, String referencia) {
        var t = v.totales();
        return new ValorizacionResponse(
                v.fuente(), v.tenant(), v.calculadoPor(), v.consultadoEn(), referencia,
                v.supuestos().stream().map(s -> new SupuestoResponse(s.codigo(), s.mensaje()))
                        .toList(),
                new TotalesResponse(t.neto(), t.descuento(), t.netoConDescuento()),
                v.lineas().stream().map(Dtos::lineaDe).toList());
    }

    private static LineaResponse lineaDe(LineaValorizada l) {
        return new LineaResponse(l.codigoItem(), l.cantidad(), l.neto(), l.descuento(),
                l.netoConDescuento(), l.creadaPorPromo(),
                l.bonificaciones().stream().map(Dtos::bonificacionDe).toList());
    }

    private static BonificacionResponse bonificacionDe(BonificacionAplicada b) {
        return new BonificacionResponse(b.id(), b.nombre(), b.descuento(),
                b.condiciones().stream().map(Dtos::condicionDe).toList());
    }

    public static CondicionResponse condicionDe(Condicion c) {
        return new CondicionResponse(c.tipo().name(), c.descripcion(), c.valores(),
                c.invertida(), c.cantidadMinima());
    }

    // --- El catalogo. Lo necesitan el endpoint publico (/v1/{tenant}/criterios, con api-key de
    // alcance ADMIN) y el panel (/admin/v1/..., con la sesion). Un solo mapeo: el panel tiene que
    // mostrar lo mismo que ve un integrador, no una segunda version que se despegue.

    public static CriterioResponse criterioDe(Criterio c, Set<String> articulosResueltos,
                                              boolean aplicaATodo) {
        var articulos = articulosResueltos != null
                ? articulosResueltos.stream().sorted().toList()
                : List.<String>of();

        return new CriterioResponse(
                c.id(),
                c.nombre(),
                c.descripcion(),
                c.activo(),
                c.vigencia() == null ? null : c.vigencia().desde(),
                c.vigencia() == null ? null : c.vigencia().hasta(),
                c.clientes(),
                c.condicionesHoja().stream().map(Dtos::condicionDe).toList(),
                c.modificadores().stream().map(m -> modificadorDe(c, m)).toList(),
                aplicaATodo,
                articulos);
    }

    private static ModificadorResponse modificadorDe(Criterio criterio, Modificador m) {
        var aplicaA = criterio.condicionesDe(m).stream().map(Dtos::condicionDe).toList();

        return switch (m.operacion()) {
            case Operacion.Descuento d -> new ModificadorResponse(
                    "DESCUENTO", m.descripcion(), d.descuento(), d.tope(),
                    null, null, null, null, null, aplicaA);

            case Operacion.EscalaDeDescuento e -> new ModificadorResponse(
                    "ESCALA", m.descripcion(), null, null,
                    e.tramos().stream()
                            .map(t -> new TramoResponse(t.desdeCantidad(), t.descuento()))
                            .toList(),
                    null, null, null, null, aplicaA);

            case Operacion.ItemSinCargo i -> new ModificadorResponse(
                    "ITEM_SIN_CARGO", m.descripcion(), null, null, null,
                    i.codigoItem(), i.cantidad(), null, null, aplicaA);

            // No se oculta: si GESCOM trae un modificador que no sabemos interpretar, el
            // consumidor tiene que verlo. El numero de /valorizaciones igual sale bien, porque
            // lo da eval-pedido; lo que no podemos es explicarlo.
            case null -> new ModificadorResponse(
                    "NO_RECONOCIDO", m.descripcion(), null, null, null, null, null,
                    m.tipo(), m.crudo(), aplicaA);
        };
    }
}
