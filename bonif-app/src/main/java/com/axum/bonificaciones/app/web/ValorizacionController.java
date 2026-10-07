package com.axum.bonificaciones.app.web;

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
        var resultado = valorizador.valorizar(tenant, Dtos.pedidoDe(pedido));
        return Dtos.respuestaDe(resultado, pedido.referencia());
    }
}
