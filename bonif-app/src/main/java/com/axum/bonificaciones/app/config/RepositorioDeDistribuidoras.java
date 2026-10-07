package com.axum.bonificaciones.app.config;

import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras.Distribuidora;
import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras.DistribuidoraDesconocidaException;
import com.axum.bonificaciones.app.config.ConfiguracionDeDistribuidoras.Gescom;
import com.axum.bonificaciones.app.seguridad.CifradoDeSecretos;
import com.axum.bonificaciones.app.seguridad.ContextoDeLlamada;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las distribuidoras, en la base.
 *
 * Reemplaza a la configuracion por variable de entorno, que era un andamio: esto escala a ~1000
 * distribuidoras y un alta no puede implicar reiniciar el servicio.
 *
 * `host` y `realm` salen del codigo por convencion y la base los guarda NULL; se pueden pisar por
 * fila porque con mil distribuidoras alguna no va a seguir la convencion, y descubrirlo no puede
 * implicar un release.
 */
@Repository
@ConditionalOnProperty(name = "bonificaciones.distribuidoras-en-base", havingValue = "true",
        matchIfMissing = true)
public class RepositorioDeDistribuidoras implements Distribuidoras {

    @Override
    public String origen() {
        return "BASE";
    }


    private final JdbcClient jdbc;
    private final CifradoDeSecretos cifrado;

    RepositorioDeDistribuidoras(JdbcClient jdbc, CifradoDeSecretos cifrado) {
        this.jdbc = jdbc;
        this.cifrado = cifrado;
    }

    /** El host que le corresponde a un codigo si la fila no lo pisa. */
    public static String hostPorConvencion(String codigo) {
        return "https://" + codigo + ".gescom.online";
    }

    /** El realm de Keycloak que le corresponde a un codigo si la fila no lo pisa. */
    public static String realmPorConvencion(String codigo) {
        return "gcw-" + codigo;
    }

    public Optional<Distribuidora> buscar(String codigo) {
        return jdbc.sql("""
                        SELECT codigo, host, realm, gescom_usuario, gescom_clave
                        FROM distribuidora
                        WHERE codigo = :codigo AND activa = 1
                        """)
                .param("codigo", codigo)
                .query((rs, n) -> new Distribuidora(new Gescom(
                        valorOConvencion(rs.getString("host"), hostPorConvencion(codigo)),
                        valorOConvencion(rs.getString("realm"), realmPorConvencion(codigo)),
                        rs.getString("gescom_usuario"),
                        cifrado.descifrar(rs.getString("gescom_clave")))))
                .optional();
    }

    @Override
    public Distribuidora requerir(String codigo) {
        return buscar(codigo).orElseThrow(() -> new DistribuidoraDesconocidaException(codigo));
    }

    /** Los codigos de las distribuidoras activas. Lo informa /health. */
    @Override
    public List<String> codigosActivos() {
        return jdbc.sql("SELECT codigo FROM distribuidora WHERE activa = 1 ORDER BY codigo")
                .query(String.class)
                .list();
    }

    /**
     * Todas, con lo que el panel necesita para diagnosticar. Incluye las inactivas: una
     * distribuidora que "desaparecio" del listado es una pregunta de soporte.
     *
     * Devuelve el {@code usuario} de GESCOM a proposito -- y nunca la clave. Los usuarios de dos
     * distribuidoras se parecen peligrosamente (`apiaxum` y `axumapi` son dos distintas, de dos
     * distribuidoras distintas), asi que ver con cual quedo cargada es lo que permite descubrir
     * que alguien copio de la coleccion de Postman equivocada.
     */
    public List<Resumen> listar() {
        return jdbc.sql("""
                        SELECT codigo, nombre, host, realm, gescom_usuario, activa,
                               creada_en, creada_por, actualizada_en, actualizada_por
                        FROM distribuidora
                        ORDER BY codigo
                        """)
                .query((rs, n) -> {
                    var codigo = rs.getString("codigo");
                    return new Resumen(
                            codigo,
                            rs.getString("nombre"),
                            valorOConvencion(rs.getString("host"), hostPorConvencion(codigo)),
                            valorOConvencion(rs.getString("realm"), realmPorConvencion(codigo)),
                            rs.getString("host") != null || rs.getString("realm") != null,
                            rs.getString("gescom_usuario"),
                            rs.getBoolean("activa"),
                            rs.getTimestamp("creada_en").toInstant(),
                            rs.getString("creada_por"),
                            rs.getTimestamp("actualizada_en").toInstant(),
                            rs.getString("actualizada_por"));
                })
                .list();
    }

    /**
     * @param host           el que se usa de verdad, ya resuelto por convencion si la fila no lo pisa
     * @param fueraDeConvencion true cuando la fila pisa host o realm. Vale verlo: lo normal es que
     *                          salgan del codigo, y una excepcion explica comportamientos raros
     */
    public record Resumen(String codigo, String nombre, String host, String realm,
                          boolean fueraDeConvencion, String usuario, boolean activa,
                          java.time.Instant creadaEn, String creadaPor,
                          java.time.Instant actualizadaEn, String actualizadaPor) {}

    public boolean existe(String codigo) {
        return jdbc.sql("SELECT COUNT(1) FROM distribuidora WHERE codigo = :codigo")
                .param("codigo", codigo)
                .query(Integer.class)
                .single() > 0;
    }

    /**
     * Da de alta una distribuidora. La clave se guarda cifrada.
     *
     * No valida la credencial: eso lo hace el servicio de administracion ANTES de llamar aca, para
     * que no quede en la base una distribuidora que no anda.
     */
    public void crear(String codigo, String nombre, String host, String realm,
                      String usuario, String clave) {
        jdbc.sql("""
                        INSERT INTO distribuidora (codigo, nombre, host, realm, gescom_usuario,
                                                   gescom_clave, creada_por, actualizada_por)
                        VALUES (:codigo, :nombre, :host, :realm, :usuario, :clave, :por, :por)
                        """)
                .param("codigo", codigo)
                .param("nombre", nombre)
                .param("host", host)
                .param("realm", realm)
                .param("usuario", usuario)
                .param("clave", cifrado.cifrar(clave))
                .param("por", ContextoDeLlamada.usuario())
                .update();
    }

    public void actualizarCredenciales(String codigo, String usuario, String clave) {
        jdbc.sql("""
                        UPDATE distribuidora
                        SET gescom_usuario = :usuario, gescom_clave = :clave,
                            actualizada_en = SYSUTCDATETIME(), actualizada_por = :por
                        WHERE codigo = :codigo
                        """)
                .param("codigo", codigo)
                .param("usuario", usuario)
                .param("clave", cifrado.cifrar(clave))
                .param("por", ContextoDeLlamada.usuario())
                .update();
    }

    public long idDe(String codigo) {
        return jdbc.sql("SELECT id FROM distribuidora WHERE codigo = :codigo")
                .param("codigo", codigo)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new DistribuidoraDesconocidaException(codigo));
    }

    private static String valorOConvencion(String guardado, String convencion) {
        return guardado == null || guardado.isBlank() ? convencion : guardado;
    }
}
