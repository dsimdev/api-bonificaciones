package com.axum.bonificaciones.app.gescom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Los DTO tal cual viajan por el cable con GESCOM. No salen de este paquete.
 *
 * Verificados contra respuestas reales (eval-pedido y get-promociones de dyssa, 2026-10). La API
 * no tiene swagger ni versionado, asi que todos ignoran propiedades desconocidas: un campo nuevo
 * no puede romper el conector.
 */
final class DtosGescom {

    private DtosGescom() {}

    // --- eval-pedido: request. PascalCase y en espaniol, como los espera el RealPedidoCreateDto.

    record SobreDePedido(@JsonProperty("Pedido") Pedido pedido) {}

    record Pedido(
            @JsonProperty("Identificador") String identificador,
            @JsonProperty("CodigoCliente") String codigoCliente,
            @JsonProperty("Items") List<ItemDePedido> items) {}

    /**
     * Es CodigoItem, NO CodigoArticulo. Con el nombre equivocado GESCOM ignora el item en
     * silencio y la respuesta termina siendo "Error desconocido" -- verificado en vivo.
     */
    /**
     * PrecioUnitario es POR UNIDAD y es opcional. Verificado en vivo (dyssa, 2026-10-08): con el
     * presente el ERP valoriza con ese precio en vez del de la lista, sigue aplicando los mismos
     * criterios y sigue siendo el que calcula el descuento. Con precio Y lista juntos, gana el
     * precio para el importe, y la lista no cambia el descuento: ni siquiera en los criterios con
     * condicion ListaPrecioVenta (criterio 610: mismo 12% sin lista, con la 2 y con la 3).
     *
     * Jackson no serializa los null si la propiedad esta anotada, asi que un item sin precio viaja
     * sin el campo -- que es lo que el ERP espera para usar la lista.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ItemDePedido(
            @JsonProperty("CodigoItem") String codigoItem,
            @JsonProperty("Cantidad") BigDecimal cantidad,
            @JsonProperty("CodigoUnidad") String codigoUnidad,
            @JsonProperty("UnidadFactor") BigDecimal unidadFactor,
            @JsonProperty("CodigoListaPrecio") String codigoListaPrecio,
            @JsonProperty("PrecioUnitario") BigDecimal precioUnitario) {}

    // --- eval-pedido: respuesta. Aca los nombres vuelven a camelCase.

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

    /**
     * @param promoId  llega como NUMERO, no como string (verificado). Es el mismo id que trae
     *                 get-promociones, asi que es por donde se cruzan los dos endpoints
     * @param descuento FRACCION (0.1 = 10%). El mapeador lo pasa a porcentaje
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DetalleDeDescuento(
            Integer promoId,
            String promoNombre,
            BigDecimal descuento,
            Boolean otorgadoPorPromo) {}

    // --- get-promociones: los "criterios de venta".

    /**
     * @param codigoCondicionPrincipal el codigo de la condicion raiz: por ahi arranca la
     *                                 evaluacion. Las condiciones que no se alcanzan desde ahi no
     *                                 participan (hay huerfanas en el catalogo real)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Promocion(
            Integer id,
            String nombre,
            String descripcion,
            Boolean activo,
            String validoDesde,
            String validoHasta,
            String dominio,
            Integer codigoCondicionPrincipal,
            Integer orden,
            Boolean global,
            List<String> clientes,
            List<CondicionCruda> condiciones,
            List<ModificadorCrudo> modificadores,
            List<MarcadorCrudo> marcadores) {}

    /**
     * @param codigo el id DENTRO del criterio (no el {@code id} global): es lo que referencian
     *               codigoCondicionPrincipal, conditionCodes y dataConditionCodes
     * @param configuracionJson viene como STRING con JSON adentro, no como objeto anidado. La
     *                          clave que guarda los valores cambia segun el tipo (tags, marcas,
     *                          codigos, proveedores, lineas, rubros, familias, calibres,
     *                          subRamoCodigos) -- ver MapeadorGescom
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CondicionCruda(
            Integer id,
            Integer codigo,
            String descripcion,
            Integer orden,
            String tipo,
            String configuracionJson) {}

    /**
     * OJO: descuento, dataConditionCodes y allowOverlap NO son campos de este nivel -- viajan
     * DENTRO de configuracionJson, igual que en las condiciones. Verificado contra el catalogo
     * real de dyssa; modelarlos como campos sueltos los dejaba todos en null y el descuento salia
     * 0 para todos los criterios.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModificadorCrudo(
            Integer id,
            Integer codigo,
            String descripcion,
            Integer orden,
            String tipo,
            String configuracionJson) {}

    /**
     * Marcadores (ItemQMarker). En todo el catalogo real de dyssa vienen con configuracionJson
     * null, asi que no se interpretan: se leen para no perderlos de vista si algun dia traen algo.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MarcadorCrudo(Integer codigo, String descripcion, String tipo, String configuracionJson) {}
}
