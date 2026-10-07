package com.axum.bonificaciones.app.admin;

import com.axum.bonificaciones.app.gescom.ValorizadorGescom;
import com.axum.bonificaciones.app.web.Dtos;
import com.axum.bonificaciones.core.model.LineaValorizada;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Probar una valorizacion desde el panel, viendo las tres capas a la vez.
 *
 * <b>Para que existe.</b> Cuando una tienda dice "el descuento esta mal", hay tres sospechosos y
 * desde afuera son indistinguibles: el ERP (que es el que calcula), nuestra normalizacion (que
 * convierte la fraccion de GESCOM en porcentaje), o lo que la tienda muestra en pantalla. Esta
 * pantalla devuelve lo que le mandamos al ERP, lo que el ERP contesto <b>crudo</b> y lo que
 * devolvemos nosotros: si el crudo y el nuestro coinciden, el problema esta en la tienda.
 *
 * Usa el mismo pedido y el mismo camino que {@code POST /v1/{tenant}/valorizaciones}: si probara
 * por otro lado, el resultado no diria nada sobre el problema real.
 *
 * Sigue siendo solo lectura: eval-pedido es dry-run.
 *
 * Va bajo /admin y con la sesion del panel, NO con la api-key de la tienda. El crudo del ERP es
 * back-office y no sale por el contrato publico.
 */
@RestController
@RequestMapping("/admin/v1/distribuidoras/{codigo}/diagnostico")
@ConditionalOnProperty(name = "bonificaciones.distribuidoras-en-base", havingValue = "true",
        matchIfMissing = true)
public class DiagnosticoController {

    private final ValorizadorGescom valorizador;

    DiagnosticoController(ValorizadorGescom valorizador) {
        this.valorizador = valorizador;
    }

    @Operation(summary = "Valoriza mostrando las tres capas: lo que pedimos, el crudo del ERP y "
            + "lo que devolvemos",
            description = "Para soporte: sirve para decidir si un descuento mal esta mal en el "
                    + "ERP, en nuestra normalizacion o en la tienda. A diferencia del endpoint "
                    + "publico, NO falla cuando el ERP devuelve lineas incoherentes -- las "
                    + "marca, porque ese es justo el caso que hay que poder mirar.")
    @PostMapping("/valorizacion")
    public Diagnostico valorizar(@PathVariable String codigo,
                                 @Valid @RequestBody Dtos.PedidoRequest pedido) {
        var d = valorizador.diagnosticar(codigo, Dtos.pedidoDe(pedido));
        var noCierran = d.valorizacion().lineasQueNoCierran().stream()
                .map(LineaValorizada::codigoItem)
                .toList();
        var desajustadas = d.comparacion().stream()
                .filter(c -> !c.coincide())
                .map(ValorizadorGescom.Comparacion::codigoItem)
                .toList();
        return new Diagnostico(
                d.pedidoEnviado(),
                d.respuestaCruda(),
                Dtos.respuestaDe(d.valorizacion(), pedido.referencia()),
                d.comparacion(),
                noCierran,
                veredicto(noCierran, desajustadas));
    }

    private String veredicto(List<String> noCierran, List<String> desajustadas) {
        if (!desajustadas.isEmpty()) {
            // Este es el unico caso donde el culpable somos nosotros sin lugar a dudas: el ERP
            // dijo un numero y nosotros exponemos otro.
            return "NUESTRO BUG: lo que devolvemos no coincide con lo que dijo el ERP en "
                    + String.join(", ", desajustadas) + ". Mira la comparacion de abajo: el "
                    + "descuento del ERP es una FRACCION (0.1) y el nuestro un PORCENTAJE (10), "
                    + "asi que la relacion tiene que ser exactamente x100.";
        }
        if (!noCierran.isEmpty()) {
            return "El ERP devolvio lineas donde el neto con descuento no se corresponde con el "
                    + "neto y el descuento (" + String.join(", ", noCierran) + "). El endpoint "
                    + "publico rechaza esto con RESPUESTA_INCOHERENTE: la tienda nunca ve ese "
                    + "numero. El problema es del ERP.";
        }
        return "El ERP respondio, los numeros cierran y lo que devolvemos coincide con lo que "
                + "dijo el ERP. Si lo que muestra la tienda no coincide con 'nuestraRespuesta', "
                + "EL PROBLEMA ESTA EN LA TIENDA. Antes de cerrarlo: verifica que la tienda este "
                + "mandando la misma listaPrecio que uso para mostrar el precio, porque la lista "
                + "cambia el precio y el porcentaje de descuento sale igual en las dos.";
    }

    /**
     * @param pedidoEnviadoAlErp   con los nombres de campo de GESCOM, para pegarlo en Postman
     * @param respuestaCrudaDelErp sin normalizar. Aca el descuento viene como FRACCION (0.1)
     * @param nuestraRespuesta     identica a la que recibe la tienda. Descuento en PORCENTAJE (10)
     * @param comparacion          linea por linea, el ERP contra nosotros
     * @param lineasQueNoCierran   vacio es lo normal
     */
    public record Diagnostico(String pedidoEnviadoAlErp,
                              String respuestaCrudaDelErp,
                              Dtos.ValorizacionResponse nuestraRespuesta,
                              List<ValorizadorGescom.Comparacion> comparacion,
                              List<String> lineasQueNoCierran,
                              String veredicto) {}
}
