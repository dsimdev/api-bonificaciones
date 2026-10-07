package com.axum.bonificaciones.app.web;

import com.axum.bonificaciones.core.model.BonificacionAplicada;
import com.axum.bonificaciones.core.model.CalculadoPor;
import com.axum.bonificaciones.core.model.Condicion;
import com.axum.bonificaciones.core.model.Fuente;
import com.axum.bonificaciones.core.model.ItemAValorizar;
import com.axum.bonificaciones.core.model.LineaValorizada;
import com.axum.bonificaciones.core.model.PedidoAValorizar;
import com.axum.bonificaciones.core.model.Valorizacion;
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

    public record ItemRequest(
            @NotBlank String codigo,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal cantidad,
            String unidad,
            BigDecimal unidadFactor) {

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

    public record CriteriosResponse(
            Fuente fuente,
            String tenant,
            OffsetDateTime consultadoEn,
            int total,
            List<CriterioResponse> criterios) {}

    /**
     * @param condiciones solo las que estan EN JUEGO: se camina el arbol desde la condicion raiz.
     *                    El ERP devuelve condiciones huerfanas que no participan de la evaluacion
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
            List<ModificadorResponse> bonificaciones) {}

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
                        i.factorODefecto()))
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
}
