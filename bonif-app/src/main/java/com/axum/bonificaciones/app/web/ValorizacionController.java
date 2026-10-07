package com.axum.bonificaciones.app.web;

import com.axum.bonificaciones.core.model.BonificacionAplicada;
import com.axum.bonificaciones.core.model.Condicion;
import com.axum.bonificaciones.core.model.ItemAValorizar;
import com.axum.bonificaciones.core.model.LineaValorizada;
import com.axum.bonificaciones.core.model.PedidoAValorizar;
import com.axum.bonificaciones.core.model.Valorizacion;
import com.axum.bonificaciones.core.puerto.Valorizador;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/{tenant}")
public class ValorizacionController {

    private final Valorizador valorizador;

    ValorizacionController(Valorizador valorizador) {
        this.valorizador = valorizador;
    }

    @Operation(summary = "Valoriza un pedido y devuelve el descuento que le corresponde",
            description = "Idempotente y sin efectos: no crea ni modifica el pedido. El descuento "
                    + "se expone como PORCENTAJE (10 = 10%). `calculadoPor` dice si el numero lo "
                    + "dio el ERP o lo calculo este gateway.")
    @PostMapping("/valorizaciones")
    public Dtos.ValorizacionResponse valorizar(@PathVariable String tenant,
                                               @Valid @RequestBody Dtos.PedidoRequest pedido) {
        var items = pedido.items().stream()
                .map(i -> new ItemAValorizar(i.codigo(), i.cantidad(), i.unidadODefecto(),
                        i.factorODefecto()))
                .toList();
        var resultado = valorizador.valorizar(tenant,
                new PedidoAValorizar(pedido.cliente(), pedido.listaPrecio(), items));
        return aResponse(resultado);
    }

    private Dtos.ValorizacionResponse aResponse(Valorizacion v) {
        return new Dtos.ValorizacionResponse(v.fuente(), v.tenant(), v.calculadoPor(),
                v.consultadoEn(), v.lineas().stream().map(this::aLinea).toList());
    }

    private Dtos.LineaResponse aLinea(LineaValorizada l) {
        return new Dtos.LineaResponse(l.codigoItem(), l.cantidad(), l.neto(), l.descuento(),
                l.netoConDescuento(), l.creadaPorPromo(),
                l.bonificaciones().stream().map(this::aBonificacion).toList());
    }

    private Dtos.BonificacionResponse aBonificacion(BonificacionAplicada b) {
        return new Dtos.BonificacionResponse(b.id(), b.nombre(), b.descuento(),
                b.condiciones().stream().map(this::aCondicion).toList());
    }

    private Dtos.CondicionResponse aCondicion(Condicion c) {
        return new Dtos.CondicionResponse(c.tipo().name(), c.descripcion(), c.valores(),
                c.invertida(), c.cantidadMinima());
    }
}
