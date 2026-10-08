package com.axum.bonificaciones.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El spec y la UI de Swagger.
 *
 * Quedan publicos, sin autenticacion propia, mismo criterio que el HTML del panel: no exponen
 * ningun dato real, solo la FORMA de cada endpoint. Nadie puede probar nada sin pegar una clave
 * real, y esa parte sigue pasando por el interceptor como cualquier otro pedido. Las rutas de
 * springdoc no empiezan con /v1/ ni con /admin/v1/, asi que ya quedan afuera de los dos
 * interceptores sin tener que excluirlas a mano.
 */
@Configuration
public class ConfiguracionOpenApi {

    private static final String ESQUEMA_API_KEY = "apiKey";

    @Bean
    OpenAPI bonificacionesOpenApi(
            @Value("${bonificaciones.version:desconocida}") String version,
            @Value("${bonificaciones.external-base-path:}") String externalBasePath) {

        var openApi = new OpenAPI()
                .info(new Info()
                        .title("API Bonificaciones")
                        .version(version)
                        .description("""
                                Gateway de **bonificaciones / criterios de venta**. Recibe un pedido \
                                (cliente, lista de precio, items) y devuelve que descuento le \
                                corresponde, con la traza de que bonificacion lo otorgo y por que.

                                **El numero lo da el ERP de la distribuidora, no este servicio.** \
                                GESCOM tiene su propio motor (`eval-pedido`) y ahi se delega: \
                                calcular por nuestra cuenta seria la forma mas rapida de devolver \
                                un precio que el ERP despues no reconoce. `calculadoPor` dice de \
                                donde salio el numero y hoy siempre es `ERP`.

                                ## Autenticacion
                                Header `x-api-key` en todo `/v1/**`, **atada a la distribuidora de \
                                la ruta**: la clave de una no sirve para otra. Tiene alcance: la \
                                del checkout solo puede valorizar, y el catalogo de criterios pide \
                                alcance ADMIN porque es la estructura comercial completa y la clave \
                                del checkout vive en un navegador.

                                `/health` no pide clave. `/admin/**` usa la sesion del panel \
                                (`Authorization: Bearer`), que son usuarios con nombre y no una \
                                clave compartida.

                                ## Para quien programa el checkout de una tienda
                                Con `POST /v1/{tenant}/valorizaciones` alcanza. El resto es \
                                administracion. El detalle completo esta en \
                                `docs/guia-de-integracion.md`: el descuento va en **porcentaje** \
                                (10 = 10%), los importes vienen **sin redondear**, hay que mandar \
                                el carrito **entero** y la **misma lista de precio** que se uso \
                                para mostrar el precio.

                                ## Errores
                                Todo error trae `{codigo, mensaje, crudo}`, nunca solo el status \
                                HTTP: el `codigo` es lo que hay que ramificar. El ERP tapa casi \
                                todos sus errores con un generico, asi que cuando se puede \
                                distinguir que paso se devuelve un codigo preciso, y cuando no, el \
                                crudo en vez de inventar.
                                """))
                .components(new Components()
                        .addSecuritySchemes(ESQUEMA_API_KEY, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("x-api-key")
                                .description("""
                                        La clave de la distribuidora que va en la ruta. Se omite \
                                        en /health y en /admin/**, que usa la sesion del panel.""")))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA_API_KEY));

        // Sin proxy anidado (externalBasePath vacio) no se toca "servers": springdoc autodetecta
        // el host del pedido, que ya es el correcto. Detras del proxy, springdoc solo ve lo que ve
        // Spring (localhost:8081, nunca /api/bonificaciones), asi que "Try it out" pegaria ahi y
        // fallaria desde el navegador, que no puede llegar a esa direccion.
        if (!externalBasePath.isBlank()) {
            openApi.addServersItem(new Server().url(externalBasePath));
        }
        return openApi;
    }
}
