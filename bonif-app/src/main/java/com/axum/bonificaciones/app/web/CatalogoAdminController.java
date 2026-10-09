package com.axum.bonificaciones.app.web;

import com.axum.bonificaciones.app.gescom.ArticulosGescom;
import com.axum.bonificaciones.app.gescom.CatalogoGescom;
import com.axum.bonificaciones.app.gescom.ClientesGescom;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El catalogo para el panel: lo mismo que {@code /v1/{tenant}/criterios} pero con la sesion del
 * panel en vez de una api-key.
 *
 * Existe para que el panel NO tenga que guardar una api-key de alcance ADMIN de cada
 * distribuidora. En el panel de MotorFiscal eso se resolvio al reves -- la pantalla de calculo
 * pide la clave propia del tenant y termina guardandola en el sessionStorage del navegador -- y
 * es justo lo que no queremos repetir: una clave de produccion mas, dando vueltas en un browser,
 * para una pantalla de consulta.
 *
 * Devuelve el mismo payload que el endpoint publico, armado con el mismo codigo: si el panel
 * mostrara una version propia del catalogo, dejaria de servir para contestarle a un integrador
 * que ve otra cosa.
 */
@RestController
@RequestMapping("/admin/v1/distribuidoras/{codigo}")
public class CatalogoAdminController {

    private final CatalogoGescom catalogo;
    private final ArticulosGescom articulos;
    private final ClientesGescom clientes;
    private final Clock reloj;

    CatalogoAdminController(CatalogoGescom catalogo, ArticulosGescom articulos,
                            ClientesGescom clientes, Clock reloj) {
        this.catalogo = catalogo;
        this.articulos = articulos;
        this.clientes = clientes;
        this.reloj = reloj;
    }

    @Operation(summary = "Los criterios de venta de una distribuidora, para el panel",
            description = "Identico a GET /v1/{tenant}/criterios, con la sesion del panel. Por "
                    + "defecto solo los vigentes; incluirNoVigentes=true suma los vencidos e "
                    + "inactivos, que es lo que hace falta para contestar por que una promo dejo "
                    + "de aplicar.")
    @GetMapping("/criterios")
    public Dtos.CriteriosResponse criterios(
            @PathVariable String codigo,
            @Parameter(description = "Incluir los criterios vencidos o inactivos")
            @RequestParam(defaultValue = "false") boolean incluirNoVigentes,
            @Parameter(description = "Fecha a la que evaluar la vigencia; por defecto, hoy")
            @RequestParam(required = false) LocalDate fecha,
            @Parameter(description = "Codigo de cliente: filtra solo los criterios que le aplican")
            @RequestParam(required = false) String cliente) {
        return CriteriosController.catalogoDe(catalogo, articulos, clientes, reloj, codigo,
                incluirNoVigentes, fecha, cliente);
    }
}
