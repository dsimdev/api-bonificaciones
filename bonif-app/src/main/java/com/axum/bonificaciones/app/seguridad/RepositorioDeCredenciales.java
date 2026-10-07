package com.axum.bonificaciones.app.seguridad;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las api-keys con las que las tiendas nos llaman.
 *
 * Se guarda el HASH, no la clave: es nuestra, nunca hace falta recuperarla, solo compararla. Si
 * alguien la pierde, se regenera. SHA-256 y no BCrypt a proposito: una api-key es un secreto
 * aleatorio de 256 bits, no una contrasenia que tipea una persona -- no hay diccionario que
 * atacar, y esto se valida en CADA request de un checkout.
 */
@Repository
@ConditionalOnProperty(name = "bonificaciones.distribuidoras-en-base", havingValue = "true",
        matchIfMissing = true)
public class RepositorioDeCredenciales {

    private static final String PREFIJO = "bon_";

    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    RepositorioDeCredenciales(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String hashear(String clave) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(clave.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public Optional<Credencial> buscarPorClave(String clave) {
        return jdbc.sql("""
                        SELECT c.id, d.codigo AS tenant, c.alcance, c.descripcion
                        FROM credencial c
                        JOIN distribuidora d ON d.id = c.distribuidora_id
                        WHERE c.clave_hash = :hash AND c.revocada = 0 AND d.activa = 1
                        """)
                .param("hash", hashear(clave))
                .query((rs, n) -> new Credencial(rs.getLong("id"), rs.getString("tenant"),
                        Credencial.Alcance.valueOf(rs.getString("alcance")),
                        rs.getString("descripcion")))
                .optional();
    }

    /**
     * Genera una clave nueva para una distribuidora y revoca las anteriores del mismo alcance.
     *
     * @return la clave en claro. Es la UNICA vez que se puede ver: despues solo queda el hash
     */
    public String generar(long distribuidoraId, Credencial.Alcance alcance, String descripcion,
                          String creadaPor) {
        jdbc.sql("""
                        UPDATE credencial SET revocada = 1
                        WHERE distribuidora_id = :id AND alcance = :alcance AND revocada = 0
                        """)
                .param("id", distribuidoraId)
                .param("alcance", alcance.name())
                .update();

        var bytes = new byte[32];
        random.nextBytes(bytes);
        var clave = PREFIJO + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        jdbc.sql("""
                        INSERT INTO credencial (distribuidora_id, clave_hash, alcance, descripcion,
                                                creada_por)
                        VALUES (:id, :hash, :alcance, :descripcion, :por)
                        """)
                .param("id", distribuidoraId)
                .param("hash", hashear(clave))
                .param("alcance", alcance.name())
                .param("descripcion", descripcion)
                .param("por", creadaPor)
                .update();

        return clave;
    }

    /** Para el panel: que claves tiene una distribuidora, sin mostrarlas. */
    public List<Resumen> listarDe(String tenant) {
        return jdbc.sql("""
                        SELECT c.alcance, c.descripcion, c.creada_en, c.creada_por
                        FROM credencial c
                        JOIN distribuidora d ON d.id = c.distribuidora_id
                        WHERE d.codigo = :tenant AND c.revocada = 0
                        ORDER BY c.alcance
                        """)
                .param("tenant", tenant)
                .query((rs, n) -> new Resumen(rs.getString("alcance"), rs.getString("descripcion"),
                        rs.getTimestamp("creada_en").toInstant().toString(),
                        rs.getString("creada_por")))
                .list();
    }

    public record Resumen(String alcance, String descripcion, String creadaEn, String creadaPor) {}
}
