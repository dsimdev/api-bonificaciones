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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Traduce lo que devuelve GESCOM al modelo normalizado. Nada de esto cruza hacia afuera. */
@Component
class MapeadorGescom {

    /** GESCOM da el descuento como fraccion; el contrato lo expone como porcentaje. */
    static final BigDecimal A_PORCENTAJE = new BigDecimal("100");

    private final ObjectMapper json;

    MapeadorGescom(ObjectMapper json) {
        this.json = json;
    }

    Criterio aCriterio(String tenant, DtosGescom.Promocion p) {
        return new Criterio(
                Fuente.GESCOM,
                tenant,
                p.id(),
                p.nombre(),
                !Boolean.FALSE.equals(p.activo()),
                new Vigencia(fecha(p.validoDesde()), fecha(p.validoHasta())),
                p.condiciones() == null ? List.of()
                        : p.condiciones().stream().map(this::aCondicion).toList(),
                p.modificadores() == null ? List.of()
                        : p.modificadores().stream().map(this::aModificador).toList(),
                p.clientes());
    }

    private Condicion aCondicion(DtosGescom.CondicionCruda c) {
        var config = parsear(c.configuracionJson());
        return new Condicion(
                c.codigo(),
                tipo(c.tipo()),
                valores(config),
                config.path("inverted").asBoolean(false),
                config.hasNonNull("requiredQuantity") ? config.get("requiredQuantity").asInt() : null,
                enteros(config.path("conditionCodes")),
                c.configuracionJson());
    }

    private Modificador aModificador(DtosGescom.ModificadorCrudo m) {
        var descuento = m.descuento() == null ? BigDecimal.ZERO
                : m.descuento().multiply(A_PORCENTAJE);
        return new Modificador(
                new Operacion.Descuento(descuento, null),
                m.dataConditionCodes(),
                Boolean.TRUE.equals(m.allowOverlap()));
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
     * Los valores de la condicion (codigos de marca, tags, etc.).
     *
     * ADVERTENCIA: el nombre de la clave que los contiene NO esta verificado contra una respuesta
     * real de get-promociones -- nunca vimos una. Por eso no se busca una clave puntual: se toma
     * el primer array de escalares que no sea conditionCodes. Cuando haya un JSON real, confirmar
     * y simplificar esto.
     */
    private List<String> valores(JsonNode config) {
        var campos = config.fields();
        while (campos.hasNext()) {
            var campo = campos.next();
            if (campo.getKey().equals("conditionCodes")) continue;
            var valor = campo.getValue();
            if (valor.isArray() && (valor.isEmpty() || valor.get(0).isValueNode())) {
                var valores = new ArrayList<String>();
                valor.forEach(v -> valores.add(v.asText()));
                if (!valores.isEmpty()) return valores;
            }
        }
        return List.of();
    }

    private List<Integer> enteros(JsonNode array) {
        if (!array.isArray()) return List.of();
        var codigos = new ArrayList<Integer>();
        array.forEach(n -> codigos.add(n.asInt()));
        return codigos;
    }

    private LocalDate fecha(String valor) {
        if (valor == null || valor.isBlank()) return null;
        try {
            return LocalDate.parse(valor.substring(0, Math.min(10, valor.length())));
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            return null;
        }
    }
}
