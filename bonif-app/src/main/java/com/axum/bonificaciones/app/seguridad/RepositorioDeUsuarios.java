package com.axum.bonificaciones.app.seguridad;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Repository;

/** Los usuarios del panel. Las contrasenias se guardan con BCrypt y no se pueden recuperar. */
@Repository
@ConditionalOnProperty(name = "bonificaciones.distribuidoras-en-base", havingValue = "true",
        matchIfMissing = true)
public class RepositorioDeUsuarios {

    private final JdbcClient jdbc;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    RepositorioDeUsuarios(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Valida usuario y contrasenia.
     *
     * Si el usuario no existe, igual se corre un hash contra un valor fijo para que la respuesta
     * tarde lo mismo: sin eso, el tiempo de respuesta dice si el usuario existe.
     */
    public Optional<Usuario> autenticar(String usuario, String clave) {
        var fila = jdbc.sql("""
                        SELECT id, usuario, nombre, activo, clave_hash
                        FROM usuario WHERE usuario = :usuario
                        """)
                .param("usuario", usuario)
                .query((rs, n) -> new ConHash(
                        new Usuario(rs.getLong("id"), rs.getString("usuario"),
                                rs.getString("nombre"), rs.getBoolean("activo")),
                        rs.getString("clave_hash")))
                .optional();

        if (fila.isEmpty()) {
            bcrypt.matches(clave, "$2a$10$ZZZZZZZZZZZZZZZZZZZZZeZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ");
            return Optional.empty();
        }
        if (!fila.get().usuario().activo()) return Optional.empty();
        if (!bcrypt.matches(clave, fila.get().hash())) return Optional.empty();
        return Optional.of(fila.get().usuario());
    }

    public void crear(String usuario, String clave, String nombre, String creadoPor) {
        jdbc.sql("""
                        INSERT INTO usuario (usuario, clave_hash, nombre, creado_por)
                        VALUES (:usuario, :hash, :nombre, :creadoPor)
                        """)
                .param("usuario", usuario)
                .param("hash", bcrypt.encode(clave))
                .param("nombre", nombre)
                .param("creadoPor", creadoPor)
                .update();
    }

    public void cambiarClave(String usuario, String claveNueva) {
        jdbc.sql("UPDATE usuario SET clave_hash = :hash WHERE usuario = :usuario")
                .param("usuario", usuario)
                .param("hash", bcrypt.encode(claveNueva))
                .update();
    }

    public void desactivar(String usuario) {
        jdbc.sql("UPDATE usuario SET activo = 0 WHERE usuario = :usuario")
                .param("usuario", usuario)
                .update();
    }

    public List<Usuario> listar() {
        return jdbc.sql("SELECT id, usuario, nombre, activo FROM usuario ORDER BY usuario")
                .query((rs, n) -> new Usuario(rs.getLong("id"), rs.getString("usuario"),
                        rs.getString("nombre"), rs.getBoolean("activo")))
                .list();
    }

    public boolean hayAlguno() {
        return jdbc.sql("SELECT COUNT(1) FROM usuario").query(Integer.class).single() > 0;
    }

    public boolean existe(String usuario) {
        return jdbc.sql("SELECT COUNT(1) FROM usuario WHERE usuario = :usuario")
                .param("usuario", usuario).query(Integer.class).single() > 0;
    }

    private record ConHash(Usuario usuario, String hash) {}
}
