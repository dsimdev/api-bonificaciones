package com.axum.bonificaciones.app.gescom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Los DTO tal cual viajan por el cable con GESCOM. No salen de este paquete.
 *
 * Reconstruidos probando la API en vivo (ver C:\Dev\docs\gescom\eval-pedido.md). Todos ignoran
 * las propiedades desconocidas: la API no tiene swagger ni versionado, asi que un campo nuevo no
 * puede romper el conector.
 */
final class DtosGescom {

    private DtosGescom() {}

    // --- eval-pedido: request. Los nombres van en PascalCase y en espaniol, como los espera el
    // DTO RealPedidoCreateDto del ERP.

    record SobreDePedido(@JsonProperty("Pedido") Pedido pedido) {}

    record Pedido(
            @JsonProperty("Identificador") String identificador,
            @JsonProperty("CodigoCliente") String codigoCliente,
            @JsonProperty("Items") List<ItemDePedido> items) {}

    /**
     * Es CodigoItem, NO CodigoArticulo. Con el nombre equivocado GESCOM ignora el item en
     * silencio y la respuesta termina siendo "Error desconocido" -- verificado en vivo.
     */
    record ItemDePedido(
            @JsonProperty("CodigoItem") String codigoItem,
            @JsonProperty("Cantidad") BigDecimal cantidad,
            @JsonProperty("CodigoUnidad") String codigoUnidad,
            @JsonProperty("UnidadFactor") BigDecimal unidadFactor,
            @JsonProperty("CodigoListaPrecio") String codigoListaPrecio) {}

    // --- eval-pedido: respuesta. Aca los nombres vuelven a camelCase. Es una lista de ventas,
    // cada una con sus items.

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VentaEvaluada(Integer indiceVenta, List<ItemEvaluado> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ItemEvaluado(
            String itemCodigo,
            BigDecimal cantidad,
            BigDecimal precioNetoTotal,
            BigDecimal precioNetoTotalConDesc,
            BigDecimal descuentoTotal,
            Boolean creadoPorPromo,
            List<DetalleDeDescuento> detalleDescuento) {}

    /** descuento viene como FRACCION (0.1 = 10%). El mapeador lo pasa a porcentaje. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DetalleDeDescuento(
            String promoId,
            String promoNombre,
            BigDecimal descuento,
            Boolean otorgadoPorPromo) {}

    // --- get-promociones: los "criterios de venta".

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Promocion(
            String id,
            String nombre,
            Boolean activo,
            String validoDesde,
            String validoHasta,
            String dominio,
            List<String> clientes,
            List<CondicionCruda> condiciones,
            List<ModificadorCrudo> modificadores) {}

    /** configuracionJson viene como STRING con JSON adentro, no como objeto. Hay que parsearlo. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CondicionCruda(Integer codigo, String tipo, String configuracionJson) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModificadorCrudo(
            String tipo,
            BigDecimal descuento,
            List<Integer> dataConditionCodes,
            Boolean allowOverlap) {}
}
