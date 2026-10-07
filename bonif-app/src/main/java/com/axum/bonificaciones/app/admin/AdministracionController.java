package com.axum.bonificaciones.app.admin;

import com.axum.bonificaciones.app.config.RepositorioDeDistribuidoras;
import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El alta y la administracion de distribuidoras.
 *
 * Protegido por la sesion del panel ({@code Authorization: Bearer <token>}, ver LoginController):
 * son <b>usuarios con nombre</b>, no una clave compartida, porque hace falta saber quien dio de
 * alta que. No es por tenant: quien administra opera sobre todas.
 *
 * Es lo que llama el panel. Tambien se puede operar desde Swagger, que es como se dieron de alta
 * las primeras distribuidoras antes de que el panel existiera.
 */
@RestController
@RequestMapping("/admin/v1/distribuidoras")
@ConditionalOnProperty(name = "bonificaciones.distribuidoras-en-base", havingValue = "true",
        matchIfMissing = true)
public class AdministracionController {

    private final ServicioDeAltas altas;
    private final RepositorioDeDistribuidoras distribuidoras;

    AdministracionController(ServicioDeAltas altas, RepositorioDeDistribuidoras distribuidoras) {
        this.altas = altas;
        this.distribuidoras = distribuidoras;
    }

    @Operation(summary = "Lista las distribuidoras, con lo necesario para diagnosticarlas",
            description = "Incluye las inactivas: una distribuidora que desaparecio del listado "
                    + "es una pregunta de soporte. Nunca devuelve la clave de GESCOM.")
    @GetMapping
    public List<RepositorioDeDistribuidoras.Resumen> listar() {
        return distribuidoras.listar();
    }

    @Operation(summary = "Da de alta una distribuidora, probando sus credenciales antes de guardar",
            description = "Mintea el token contra Keycloak Y trae el catalogo. Si algo falla, NO "
                    + "se guarda nada: es preferible que el alta falle a que quede una "
                    + "distribuidora rota que nadie descubre hasta que falla su checkout.")
    @PostMapping
    public Creada crear(@Valid @RequestBody AltaRequest alta) {
        var r = altas.crear(alta.codigo(), alta.nombre(), alta.host(), alta.realm(),
                alta.usuario(), alta.clave());
        return new Creada(alta.codigo(), r.criterios(), r.claveDeLaTienda(),
                "Listo: la credencial de GESCOM anda y la distribuidora trajo " + r.criterios()
                        + " criterios. GUARDA LA CLAVE DE LA TIENDA AHORA: no se puede recuperar "
                        + "despues, solo regenerar.");
    }

    @Operation(summary = "Regenera la clave de la tienda",
            description = "La anterior queda revocada al instante. Para cuando se filtro o se "
                    + "perdio.")
    @PostMapping("/{codigo}/clave-tienda")
    public Creada regenerarClaveDeTienda(@PathVariable String codigo) {
        var clave = altas.regenerarClaveDeTienda(codigo);
        return new Creada(codigo, -1, clave,
                "Clave nueva. La anterior quedo revocada: la tienda deja de funcionar hasta que "
                        + "la cambie. Guardala ahora.");
    }

    @Operation(summary = "Cambia las credenciales de GESCOM, probandolas antes de guardar")
    @PutMapping("/{codigo}/credenciales")
    public Resultado actualizarCredenciales(@PathVariable String codigo,
                                            @Valid @RequestBody CredencialesRequest req) {
        int criterios = altas.actualizarCredenciales(codigo, req.usuario(), req.clave());
        return new Resultado(codigo, criterios,
                "Listo: las credenciales nuevas andan y trajeron " + criterios + " criterios.");
    }

    @Operation(summary = "Vuelve a probar las credenciales guardadas de una distribuidora",
            description = "Para el panel: responde si esa distribuidora anda HOY. Una clave que "
                    + "fue rotada del lado del ERP se detecta aca y no en un checkout.")
    @PostMapping("/{codigo}/verificar")
    public Resultado verificar(@PathVariable String codigo) {
        var gescom = distribuidoras.requerir(codigo).gescom();
        if (gescom == null) {
            throw new ErrorDeGateway(CodigoDeError.FUENTE_NO_CONFIGURADA,
                    "La distribuidora " + codigo + " no tiene GESCOM configurado.");
        }
        int criterios = altas.verificarGuardadas(codigo);
        return new Resultado(codigo, criterios,
                "Anda: trajo " + criterios + " criterios.");
    }

    /**
     * Los campos se llaman como en la coleccion de Postman de donde se copian, a proposito: quien
     * da de alta no tiene que traducir nada.
     *
     * @param host  opcional. Por defecto {@code https://<codigo>.gescom.online}
     * @param realm opcional. Por defecto {@code gcw-<codigo>}
     */
    public record AltaRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9-]+",
                    message = "solo minusculas, numeros y guiones (es lo que va en la URL)")
            String codigo,
            String nombre,
            @NotBlank String usuario,
            @NotBlank String clave,
            String host,
            String realm) {}

    public record CredencialesRequest(@NotBlank String usuario, @NotBlank String clave) {}

    /** @param criterios cuantos trajo. Es la prueba de que anda, no un dato de color. */
    public record Resultado(String codigo, int criterios, String mensaje) {}

    /**
     * @param claveDeLaTienda en claro, y es la UNICA vez que se ve
     * @param criterios -1 cuando no corresponde (al regenerar la clave no se consulta el ERP)
     */
    public record Creada(String codigo, int criterios, String claveDeLaTienda, String mensaje) {}
}
