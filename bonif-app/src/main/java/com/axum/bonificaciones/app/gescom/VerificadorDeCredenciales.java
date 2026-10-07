package com.axum.bonificaciones.app.gescom;

import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras.Gescom;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/**
 * Prueba de punta a punta que unas credenciales de GESCOM sirven.
 *
 * No alcanza con mintear el token: un token valido contra un realm equivocado tambien se mintea.
 * Lo que prueba que la distribuidora quedo bien configurada es **traer su catalogo**. Por eso
 * devuelve cuantos criterios trajo: ese numero es lo que se le muestra a quien da de alta
 * ("anda, trajo 70 criterios"), y es un smoke test completo hecho por alguien que no sabe lo que
 * es un token.
 */
@Component
public class VerificadorDeCredenciales {

    private static final ParameterizedTypeReference<List<Object>> CRITERIOS =
            new ParameterizedTypeReference<>() {};

    private final ClienteGescom cliente;

    VerificadorDeCredenciales(ClienteGescom cliente) {
        this.cliente = cliente;
    }

    /**
     * @return cuantos criterios devolvio get-promociones
     * @throws com.axum.bonificaciones.app.dominio.ErrorDeGateway si las credenciales no sirven o
     *         GESCOM no responde. El llamador NO guarda nada en ese caso
     */
    public int contarCriterios(String codigo, Gescom config) {
        var criterios = cliente.getConCredenciales(config, "ventas", "get-promociones", CRITERIOS);
        return criterios == null ? 0 : criterios.size();
    }
}
