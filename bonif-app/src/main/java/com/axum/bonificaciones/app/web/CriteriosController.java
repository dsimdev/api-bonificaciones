package com.axum.bonificaciones.app.web;

import com.axum.bonificaciones.core.model.Fuente;
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
 *
 * Pide api-key de alcance ADMIN: es la estructura comercial completa de la distribuidora y la
 * clave del checkout vive en un navegador. El panel lo lee por /admin con la sesion, no con una
 * api-key -- ver CatalogoAdminController.
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
        return catalogoDe(catalogo, reloj, tenant, incluirNoVigentes, fecha);
    }

    /**
     * Compartido con el panel: el que opera tiene que ver EXACTAMENTE lo mismo que ve el
     * integrador, no una segunda version del catalogo que se despegue de esta.
     */
    static Dtos.CriteriosResponse catalogoDe(CatalogoDeCriterios catalogo, Clock reloj,
                                             String tenant, boolean incluirNoVigentes,
                                             LocalDate fecha) {
        var alDia = fecha != null ? fecha : LocalDate.now(reloj);

        var criterios = catalogo.criterios(tenant).stream()
                .filter(c -> incluirNoVigentes || c.aplicableEn(alDia))
                .map(Dtos::criterioDe)
                .toList();

        return new Dtos.CriteriosResponse(Fuente.GESCOM, tenant, OffsetDateTime.now(reloj),
                criterios.size(), criterios);
    }
}
