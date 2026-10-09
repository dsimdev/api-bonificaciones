package com.axum.bonificaciones.app.web;

import com.axum.bonificaciones.app.gescom.ArticulosGescom;
import com.axum.bonificaciones.app.gescom.CatalogoGescom;
import com.axum.bonificaciones.app.gescom.ClientesGescom;
import com.axum.bonificaciones.app.gescom.ResolvedorDeArticulos;
import com.axum.bonificaciones.core.model.Criterio;
import com.axum.bonificaciones.core.model.Fuente;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El catalogo de criterios de venta de una distribuidora.
 *
 * Cada criterio viene con los codigos de articulo ya resueltos: "marca pepsico-11" se traduce a
 * los articulos que tienen esa marca en GESCOM. Con ?cliente= ademas filtra los criterios que
 * aplican a ese cliente.
 */
@RestController
@RequestMapping("/v1/{tenant}")
public class CriteriosController {

    private final CatalogoGescom catalogo;
    private final ArticulosGescom articulos;
    private final ClientesGescom clientes;
    private final Clock reloj;

    CriteriosController(CatalogoGescom catalogo, ArticulosGescom articulos,
                        ClientesGescom clientes, Clock reloj) {
        this.catalogo = catalogo;
        this.articulos = articulos;
        this.clientes = clientes;
        this.reloj = reloj;
    }

    @Operation(summary = "Lista los criterios de venta (bonificaciones) de la distribuidora",
            description = "Por defecto devuelve SOLO los vigentes y activos a la fecha. Cada "
                    + "criterio incluye `articulos`: los codigos de articulo a los que aplica, "
                    + "resueltos cruzando las condiciones con el catalogo de GESCOM. Con "
                    + "`cliente` filtra solo los que aplican a ese cliente. Los datos se cachean: "
                    + "`actualizadoEn` dice cuando se trajeron de GESCOM.")
    @GetMapping("/criterios")
    public Dtos.CriteriosResponse criterios(
            @PathVariable String tenant,
            @Parameter(description = "Incluir los criterios vencidos o inactivos")
            @RequestParam(defaultValue = "false") boolean incluirNoVigentes,
            @Parameter(description = "Fecha a la que evaluar la vigencia; por defecto, hoy")
            @RequestParam(required = false) LocalDate fecha,
            @Parameter(description = "Codigo de cliente: filtra solo los criterios que le aplican")
            @RequestParam(required = false) String cliente) {
        return catalogoDe(catalogo, articulos, clientes, reloj, tenant,
                incluirNoVigentes, fecha, cliente);
    }

    static Dtos.CriteriosResponse catalogoDe(CatalogoGescom catalogo,
                                             ArticulosGescom articulosGescom,
                                             ClientesGescom clientesGescom,
                                             Clock reloj, String tenant,
                                             boolean incluirNoVigentes, LocalDate fecha,
                                             String codigoCliente) {
        var alDia = fecha != null ? fecha : LocalDate.now(reloj);
        var indice = articulosGescom.articulos(tenant);

        ClientesGescom.DatosDeCliente datosCliente = null;
        if (codigoCliente != null && !codigoCliente.isBlank()) {
            datosCliente = clientesGescom.buscar(tenant, codigoCliente);
        }

        var todosLosCriterios = catalogo.criterios(tenant);
        final var datosClienteFinal = datosCliente;

        var criterios = todosLosCriterios.stream()
                .filter(c -> incluirNoVigentes || c.aplicableEn(alDia))
                .filter(c -> datosClienteFinal == null
                        || ResolvedorDeArticulos.aplicaAlCliente(c, datosClienteFinal))
                .map(c -> {
                    var arts = ResolvedorDeArticulos.resolver(c, indice);
                    return Dtos.criterioDe(c, arts);
                })
                .toList();

        var actualizadoInstant = catalogo.actualizadoEn(tenant);
        var actualizadoEn = actualizadoInstant != null
                ? OffsetDateTime.ofInstant(actualizadoInstant, reloj.getZone())
                : OffsetDateTime.now(reloj);

        return new Dtos.CriteriosResponse(Fuente.GESCOM, tenant, OffsetDateTime.now(reloj),
                actualizadoEn, criterios.size(), criterios);
    }
}
