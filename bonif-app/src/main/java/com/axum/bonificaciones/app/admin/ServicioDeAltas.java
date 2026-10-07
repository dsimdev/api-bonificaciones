package com.axum.bonificaciones.app.admin;

import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras.Gescom;
import com.axum.bonificaciones.app.config.RepositorioDeDistribuidoras;
import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.axum.bonificaciones.app.gescom.VerificadorDeCredenciales;
import com.axum.bonificaciones.app.seguridad.ContextoDeLlamada;
import com.axum.bonificaciones.app.seguridad.Credencial;
import com.axum.bonificaciones.app.seguridad.RepositorioDeCredenciales;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * El alta de una distribuidora.
 *
 * **La validacion en el acto no es un extra: es el motivo de que esto exista como servicio.** El
 * alta la hace alguien sin contexto, copiando de una coleccion de Postman, hasta diez veces en un
 * dia. Si una clave se copia mal y nadie la prueba, queda una distribuidora rota que nadie
 * descubre hasta que falla el checkout de esa tienda -- probablemente delante de un cliente.
 *
 * Por eso el alta prueba de punta a punta ANTES de guardar: mintea el token y trae el catalogo.
 * Si algo falla, no se guarda nada.
 */
@Service
@ConditionalOnProperty(name = "bonificaciones.distribuidoras-en-base", havingValue = "true",
        matchIfMissing = true)
public class ServicioDeAltas {

    private final RepositorioDeDistribuidoras distribuidoras;
    private final VerificadorDeCredenciales verificador;
    private final RepositorioDeCredenciales credenciales;

    ServicioDeAltas(RepositorioDeDistribuidoras distribuidoras,
                    VerificadorDeCredenciales verificador,
                    RepositorioDeCredenciales credenciales) {
        this.distribuidoras = distribuidoras;
        this.verificador = verificador;
        this.credenciales = credenciales;
    }

    /**
     * @param host  null = se usa la convencion {@code https://<codigo>.gescom.online}
     * @param realm null = se usa la convencion {@code gcw-<codigo>}
     * @return cuantos criterios trajo la distribuidora, que es la prueba de que anda de verdad
     */
    public Alta crear(String codigo, String nombre, String host, String realm,
                      String usuario, String clave) {
        if (distribuidoras.existe(codigo)) {
            throw new ErrorDeGateway(CodigoDeError.DISTRIBUIDORA_YA_EXISTE,
                    "Ya hay una distribuidora con el codigo " + codigo + ".");
        }

        var criterios = verificar(codigo, host, realm, usuario, clave);
        distribuidoras.crear(codigo, nombre, host, realm, usuario, clave);

        // La clave que va a usar la tienda. Se muestra UNA sola vez: despues solo queda el hash.
        var claveTienda = credenciales.generar(distribuidoras.idDe(codigo),
                Credencial.Alcance.VALORIZACION, "Checkout de la tienda",
                ContextoDeLlamada.usuario());
        return new Alta(criterios, claveTienda);
    }

    /**  claveDeLaTienda en claro. Es la unica vez que se puede ver */
    public record Alta(int criterios, String claveDeLaTienda) {}

    /** Cambiar las credenciales tambien las prueba antes: las claves de API tambien se rotan. */
    public int actualizarCredenciales(String codigo, String usuario, String clave) {
        var existente = distribuidoras.requerir(codigo).gescom();
        var criterios = verificar(codigo, existente.host(), existente.realm(), usuario, clave);
        distribuidoras.actualizarCredenciales(codigo, usuario, clave);
        return criterios;
    }

    /** Regenera la clave con la que la tienda nos llama. La anterior queda revocada. */
    public String regenerarClaveDeTienda(String codigo) {
        distribuidoras.requerir(codigo);
        return credenciales.generar(distribuidoras.idDe(codigo),
                Credencial.Alcance.VALORIZACION, "Checkout de la tienda",
                ContextoDeLlamada.usuario());
    }

    /** Vuelve a probar las credenciales YA guardadas. Para el panel: "esta anda hoy?". */
    public int verificarGuardadas(String codigo) {
        var g = distribuidoras.requerir(codigo).gescom();
        return verificador.contarCriterios(codigo, g);
    }

    /**
     * Prueba las credenciales y traduce el error a algo que entienda quien da de alta.
     *
     * Quien hace esto copia de una coleccion de Postman y no sabe lo que es un realm ni un 401:
     * "Keycloak rechazo las credenciales del realm gcw-x (HTTP 401)" no le dice que hacer. Y una
     * clave mal copiada es error de quien carga, no de la fuente: va 400, no 502.
     */
    private int verificar(String codigo, String host, String realm, String usuario, String clave) {
        try {
            return intentar(codigo, host, realm, usuario, clave);
        } catch (ErrorDeGateway e) {
            throw switch (e.codigo()) {
                case CREDENCIALES_INVALIDAS -> new ErrorDeGateway(
                        CodigoDeError.CREDENCIALES_RECHAZADAS,
                        "GESCOM rechazo esas credenciales para la distribuidora " + codigo
                                + ". Revisa el usuario y la clave en la coleccion de Postman, y "
                                + "que el codigo de la distribuidora sea el correcto.");
                case FUENTE_NO_DISPONIBLE -> new ErrorDeGateway(
                        CodigoDeError.FUENTE_NO_DISPONIBLE,
                        "GESCOM no responde para la distribuidora " + codigo
                                + ". No se guardo nada: proba de nuevo en un rato.");
                case COMANDO_INEXISTENTE -> new ErrorDeGateway(
                        CodigoDeError.CREDENCIALES_RECHAZADAS,
                        "La distribuidora " + codigo + " no parece existir en GESCOM. "
                                + "Revisa que el codigo este bien escrito.");
                default -> e;
            };
        }
    }

    private int intentar(String codigo, String host, String realm, String usuario, String clave) {
        var config = new Gescom(
                host == null || host.isBlank() ? RepositorioDeDistribuidoras.hostPorConvencion(codigo) : host,
                realm == null || realm.isBlank() ? RepositorioDeDistribuidoras.realmPorConvencion(codigo) : realm,
                usuario, clave);
        return verificador.contarCriterios(codigo, config);
    }
}
