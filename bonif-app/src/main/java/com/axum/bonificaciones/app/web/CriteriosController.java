package com.axum.bonificaciones.app.web;

import com.axum.bonificaciones.core.model.Condicion;
import com.axum.bonificaciones.core.model.Criterio;
import com.axum.bonificaciones.core.model.Fuente;
import com.axum.bonificaciones.core.model.Modificador;
import com.axum.bonificaciones.core.model.Operacion;
import com.axum.bonificaciones.core.puerto.CatalogoDeCriterios;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El catalogo de criterios de venta de una distribuidora.
 *
 * Es de consulta: sirve para ver que bonificaciones hay cargadas y para soporte ("por que este
 * cliente no tiene tal promo"). El numero de un pedido NO sale de aca, sale de /valorizaciones.
 */
@RestController
@RequestMapping("/v1/{tenant}")
public class CriteriosController {

    private final CatalogoDeCriterios catalogo;
    private final Clock reloj;

    CriteriosController(CatalogoDeCriterios catalogo, Clock reloj) {
        this.catalogo = catalogo;
        this.reloj = reloj;
    }

    @Operation(summary = "Lista los criterios de venta (bonificaciones) de la distribuidora",
            description = "Por defecto devuelve SOLO los vigentes y activos a la fecha: un "
                    + "criterio vencido no viaja en el payload para que el consumidor lo "
                    + "descarte. Los descuentos van en PORCENTAJE (10 = 10%).")
    @GetMapping("/criterios")
    public Dtos.CriteriosResponse criterios(
            @PathVariable String tenant,
            @Parameter(description = "Incluir los criterios vencidos o inactivos")
            @RequestParam(defaultValue = "false") boolean incluirNoVigentes,
            @Parameter(description = "Fecha a la que evaluar la vigencia; por defecto, hoy")
            @RequestParam(required = false) LocalDate fecha) {

        var alDia = fecha != null ? fecha : LocalDate.now(reloj);

        var criterios = catalogo.criterios(tenant).stream()
                .filter(c -> incluirNoVigentes || c.aplicableEn(alDia))
                .map(this::aResponse)
                .toList();

        return new Dtos.CriteriosResponse(Fuente.GESCOM, tenant, OffsetDateTime.now(reloj),
                criterios.size(), criterios);
    }

    private Dtos.CriterioResponse aResponse(Criterio c) {
        return new Dtos.CriterioResponse(
                c.id(),
                c.nombre(),
                c.descripcion(),
                c.activo(),
                c.vigencia() == null ? null : c.vigencia().desde(),
                c.vigencia() == null ? null : c.vigencia().hasta(),
                c.clientes(),
                // Solo las que estan en juego: el catalogo real trae condiciones huerfanas que
                // ningun combinador referencia y que no participan de la evaluacion.
                c.condicionesHoja().stream().map(this::aCondicion).toList(),
                c.modificadores().stream().map(m -> aModificador(c, m)).toList());
    }

    private Dtos.ModificadorResponse aModificador(Criterio criterio, Modificador m) {
        var aplicaA = criterio.condicionesDe(m).stream().map(this::aCondicion).toList();

        return switch (m.operacion()) {
            case Operacion.Descuento d -> new Dtos.ModificadorResponse(
                    "DESCUENTO", m.descripcion(), d.descuento(), d.tope(),
                    null, null, null, null, null, aplicaA);

            case Operacion.EscalaDeDescuento e -> new Dtos.ModificadorResponse(
                    "ESCALA", m.descripcion(), null, null,
                    e.tramos().stream()
                            .map(t -> new Dtos.TramoResponse(t.desdeCantidad(), t.descuento()))
                            .toList(),
                    null, null, null, null, aplicaA);

            case Operacion.ItemSinCargo i -> new Dtos.ModificadorResponse(
                    "ITEM_SIN_CARGO", m.descripcion(), null, null, null,
                    i.codigoItem(), i.cantidad(), null, null, aplicaA);

            // No se oculta: si GESCOM trae un modificador que no sabemos interpretar, el
            // consumidor tiene que verlo. El numero de /valorizaciones igual sale bien, porque
            // lo da eval-pedido; lo que no podemos es explicarlo.
            case null -> new Dtos.ModificadorResponse(
                    "NO_RECONOCIDO", m.descripcion(), null, null, null, null, null,
                    m.tipo(), m.crudo(), aplicaA);
        };
    }

    private Dtos.CondicionResponse aCondicion(Condicion c) {
        return new Dtos.CondicionResponse(c.tipo().name(), c.descripcion(), c.valores(),
                c.invertida(), c.cantidadMinima());
    }
}
