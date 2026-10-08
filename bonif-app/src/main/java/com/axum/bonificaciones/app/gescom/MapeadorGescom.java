package com.axum.bonificaciones.app.gescom;

import com.axum.bonificaciones.core.model.Condicion;
import com.axum.bonificaciones.core.model.Criterio;
import com.axum.bonificaciones.core.model.Fuente;
import com.axum.bonificaciones.core.model.Modificador;
import com.axum.bonificaciones.core.model.Operacion;
import com.axum.bonificaciones.core.model.TipoCondicion;
import com.axum.bonificaciones.core.model.Vigencia;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Traduce lo que devuelve GESCOM al modelo normalizado. Nada de esto cruza hacia afuera. */
@Component
class MapeadorGescom {

    private static final Logger log = LoggerFactory.getLogger(MapeadorGescom.class);

    /** El unico modificador observado en el catalogo real de dyssa. */
    private static final String TIPO_DESCUENTO = "DescuentoItem";

    /** GESCOM da el descuento como fraccion; el contrato lo expone como porcentaje. */
    static final BigDecimal A_PORCENTAJE = new BigDecimal("100");

    /**
     * La clave del configuracionJson que guarda los valores, por tipo de condicion.
     *
     * Cambia segun el tipo y no hay forma de deducirla: salio de leer el catalogo real de dyssa
     * (2026-10). Un tipo que no este aca cae en el camino alternativo de {@link #valores}.
     */
    private static final Map<TipoCondicion, String> CLAVE_DE_VALORES = Map.ofEntries(
            Map.entry(TipoCondicion.CODIGO_CLIENTE, "codigos"),
            Map.entry(TipoCondicion.TAG_CLIENTE, "tags"),
            Map.entry(TipoCondicion.SUBRAMO_CLIENTE, "subRamoCodigos"),
            Map.entry(TipoCondicion.CODIGO_ITEM, "codigos"),
            Map.entry(TipoCondicion.MARCA_ARTICULO, "marcas"),
            Map.entry(TipoCondicion.PROVEEDOR_ARTICULO, "proveedores"),
            Map.entry(TipoCondicion.LINEA_ARTICULO, "lineas"),
            Map.entry(TipoCondicion.RUBRO_ITEM, "rubros"),
            Map.entry(TipoCondicion.FAMILIA_ARTICULO, "familias"),
            Map.entry(TipoCondicion.CALIBRE_ARTICULO, "calibres"),
            Map.entry(TipoCondicion.LISTA_PRECIO_VENTA, "codigos"));

    private final ObjectMapper json;

    MapeadorGescom(ObjectMapper json) {
        this.json = json;
    }

    Criterio aCriterio(String tenant, DtosGescom.Promocion p) {
        return new Criterio(
                Fuente.GESCOM,
                tenant,
                p.id() == null ? null : String.valueOf(p.id()),
                p.nombre(),
                p.descripcion(),
                !Boolean.FALSE.equals(p.activo()),
                new Vigencia(fecha(p.validoDesde()), fecha(p.validoHasta())),
                p.codigoCondicionPrincipal(),
                p.orden(),
                p.condiciones() == null ? List.of()
                        : p.condiciones().stream().map(this::aCondicion).toList(),
                p.modificadores() == null ? List.of()
                        : p.modificadores().stream().map(this::aModificador).toList(),
                p.clientes());
    }

    private Condicion aCondicion(DtosGescom.CondicionCruda c) {
        var config = parsear(c.configuracionJson());
        var tipo = tipo(c.tipo());
        return new Condicion(
                c.codigo(),
                c.tipo(),
                c.descripcion(),
                tipo,
                valores(tipo, config),
                config.path("inverted").asBoolean(false),
                config.hasNonNull("requiredQuantity") ? config.get("requiredQuantity").asInt() : null,
                enteros(config.path("conditionCodes")),
                c.configuracionJson());
    }

    /**
     * El descuento, los dataConditionCodes y el allowOverlap salen del configuracionJson del
     * modificador, no de campos de ese nivel -- verificado contra el catalogo real.
     */
    private Modificador aModificador(DtosGescom.ModificadorCrudo m) {
        var config = parsear(m.configuracionJson());
        var operacion = operacion(m.tipo(), config);

        if (operacion == null) {
            // No se finge un descuento de 0: queda como noReconocido() y con su crudo. Un
            // modificador que no entendemos y pasa como "0%" es una promo que desaparece sin que
            // nadie se entere -- justo lo que la regla dura del gateway prohibe.
            log.warn("Modificador de tipo desconocido en GESCOM: tipo={} crudo={}",
                    m.tipo(), m.configuracionJson());
        }

        return new Modificador(
                m.codigo(),
                m.tipo(),
                m.descripcion(),
                operacion,
                enteros(config.path("dataConditionCodes")),
                config.path("allowOverlap").asBoolean(false),
                m.configuracionJson());
    }

    /**
     * Los tres tipos de modificador observados en el catalogo real de dyssa.
     *
     * Un tipo nuevo devuelve null: queda como noReconocido() y con su crudo, nunca como un
     * descuento de 0%. Asi aparecieron TablaDescuentoItem y AgregaGratis, que no estaban en la
     * doc de referencia.
     */
    private Operacion operacion(String tipo, JsonNode config) {
        if (tipo == null) return null;
        return switch (tipo) {
            case "DescuentoItem" -> {
                var nodo = config.path("descuento");
                yield nodo.isNumber()
                        ? new Operacion.Descuento(nodo.decimalValue().multiply(A_PORCENTAJE), null)
                        : null;
            }
            case "TablaDescuentoItem" -> new Operacion.EscalaDeDescuento(
                    tramos(config.path("tabla")),
                    config.path("descuentoPorCantidad").asBoolean(false));
            case "AgregaGratis" -> new Operacion.ItemSinCargo(
                    config.path("codigoItem").asText(null),
                    config.hasNonNull("cantidad") ? config.get("cantidad").decimalValue() : null);
            default -> null;
        };
    }

    /** La tabla es un array de pares [cantidadDesde, descuentoEnFraccion]. */
    private List<Operacion.EscalaDeDescuento.Tramo> tramos(JsonNode tabla) {
        if (!tabla.isArray()) return List.of();
        var tramos = new ArrayList<Operacion.EscalaDeDescuento.Tramo>();
        tabla.forEach(par -> {
            if (par.isArray() && par.size() >= 2) {
                tramos.add(new Operacion.EscalaDeDescuento.Tramo(
                        par.get(0).decimalValue(), porcentaje(par.get(1))));
            }
        });
        return tramos.stream()
                .sorted(java.util.Comparator.comparing(Operacion.EscalaDeDescuento.Tramo::desdeCantidad))
                .toList();
    }

    private BigDecimal porcentaje(JsonNode fraccion) {
        return fraccion.isNumber() ? fraccion.decimalValue().multiply(A_PORCENTAJE) : BigDecimal.ZERO;
    }

    /**
     * Un tipo que no conocemos cae en DESCONOCIDA y conserva su crudo, no se descarta: tragarse
     * en silencio lo que no mapeamos hace que una promo desaparezca sin que nadie se entere.
     */
    private TipoCondicion tipo(String tipo) {
        if (tipo == null) return TipoCondicion.DESCONOCIDA;
        return switch (tipo) {
            case "CodigoCliente" -> TipoCondicion.CODIGO_CLIENTE;
            case "TagCliente" -> TipoCondicion.TAG_CLIENTE;
            case "SubRamoCliente" -> TipoCondicion.SUBRAMO_CLIENTE;
            case "CodigoItem" -> TipoCondicion.CODIGO_ITEM;
            case "MarcaArticulo" -> TipoCondicion.MARCA_ARTICULO;
            case "ProveedorArticulo" -> TipoCondicion.PROVEEDOR_ARTICULO;
            case "LineaArticulo" -> TipoCondicion.LINEA_ARTICULO;
            case "RubroItem" -> TipoCondicion.RUBRO_ITEM;
            case "FamiliaArticulo" -> TipoCondicion.FAMILIA_ARTICULO;
            case "CalibreArticulo" -> TipoCondicion.CALIBRE_ARTICULO;
            case "TagItem" -> TipoCondicion.TAG_ITEM;
            case "ListaPrecioVenta" -> TipoCondicion.LISTA_PRECIO_VENTA;
            case "All" -> TipoCondicion.TODAS;
            case "Any" -> TipoCondicion.ALGUNA;
            default -> TipoCondicion.DESCONOCIDA;
        };
    }

    private JsonNode parsear(String configuracionJson) {
        if (configuracionJson == null || configuracionJson.isBlank()) {
            return json.createObjectNode();
        }
        try {
            // Viene como STRING con JSON adentro, no como objeto anidado.
            return json.readTree(configuracionJson);
        } catch (Exception e) {
            // Un configuracionJson ilegible no puede voltear la lectura del catalogo entero: la
            // condicion queda sin interpretar pero conserva el crudo.
            return json.createObjectNode();
        }
    }

    /**
     * Los valores de la condicion. La clave depende del tipo (ver CLAVE_DE_VALORES).
     *
     * Para un tipo DESCONOCIDA no sabemos la clave, asi que se toma el primer array de escalares
     * que no sea conditionCodes: es mejor devolver los valores de una condicion que no entendemos
     * que devolverla vacia. El crudo viaja igual.
     */
    private List<String> valores(TipoCondicion tipo, JsonNode config) {
        var clave = CLAVE_DE_VALORES.get(tipo);
        if (clave != null) return textos(config.path(clave));
        if (tipo == TipoCondicion.TODAS || tipo == TipoCondicion.ALGUNA) return List.of();

        var campos = config.fields();
        while (campos.hasNext()) {
            var campo = campos.next();
            if (campo.getKey().equals("conditionCodes")) continue;
            var encontrados = textos(campo.getValue());
            if (!encontrados.isEmpty()) return encontrados;
        }
        return List.of();
    }

    private List<String> textos(JsonNode array) {
        if (!array.isArray()) return List.of();
        var valores = new ArrayList<String>();
        array.forEach(v -> {
            if (v.isValueNode()) valores.add(v.asText());
        });
        return valores;
    }

    private List<Integer> enteros(JsonNode array) {
        if (!array.isArray()) return List.of();
        var codigos = new ArrayList<Integer>();
        array.forEach(n -> codigos.add(n.asInt()));
        return codigos;
    }

    /** Las fechas vienen como ISO 8601 con offset: "2023-10-30T00:00:00-03:00". */
    private LocalDate fecha(String valor) {
        if (valor == null || valor.isBlank()) return null;
        try {
            return OffsetDateTime.parse(valor).toLocalDate();
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(valor.substring(0, Math.min(10, valor.length())));
            } catch (DateTimeParseException | IndexOutOfBoundsException otra) {
                return null;
            }
        }
    }
}
