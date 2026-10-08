package com.axum.bonificaciones.app.admin;

import com.axum.bonificaciones.app.dominio.CodigoDeError;
import com.axum.bonificaciones.app.dominio.ErrorDeGateway;
import com.axum.bonificaciones.app.seguridad.ContextoDeLlamada;
import com.axum.bonificaciones.app.seguridad.RepositorioDeUsuarios;
import com.axum.bonificaciones.app.seguridad.ServicioDeSesiones;
import com.axum.bonificaciones.app.seguridad.Usuario;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entrar al panel y administrar quien puede entrar. */
@RestController
@RequestMapping("/admin/v1")
@ConditionalOnProperty(name = "bonificaciones.distribuidoras-en-base", havingValue = "true",
        matchIfMissing = true)
public class LoginController {

    private static final int MAX_INTENTOS_LOGIN = 5;
    private static final Duration VENTANA_LOGIN = Duration.ofMinutes(15);

    private final Cache<String, AtomicInteger> intentosLogin =
            Caffeine.newBuilder().expireAfterWrite(VENTANA_LOGIN).build();

    private final ServicioDeSesiones sesiones;
    private final RepositorioDeUsuarios usuarios;

    LoginController(ServicioDeSesiones sesiones, RepositorioDeUsuarios usuarios) {
        this.sesiones = sesiones;
        this.usuarios = usuarios;
    }

    @Operation(summary = "Entrar al panel",
            description = "Devuelve un token para mandar como 'Authorization: Bearer <token>' en "
                    + "el resto de /admin. Es el unico endpoint de administracion sin proteger.")
    @PostMapping("/login")
    public Sesion login(@Valid @RequestBody LoginRequest req) {
        var cuenta = intentosLogin.get(req.usuario(), u -> new AtomicInteger());
        if (cuenta.get() >= MAX_INTENTOS_LOGIN) {
            throw new ErrorDeGateway(CodigoDeError.DEMASIADOS_INTENTOS,
                    "Demasiados intentos fallidos. Espera unos minutos.");
        }
        return sesiones.ingresar(req.usuario(), req.clave())
                .map(token -> {
                    intentosLogin.invalidate(req.usuario());
                    return new Sesion(token, req.usuario(), sesiones.duracionEnMinutos());
                })
                .orElseGet(() -> {
                    cuenta.incrementAndGet();
                    throw new ErrorDeGateway(CodigoDeError.NO_AUTORIZADO,
                            "Usuario o contrasenia incorrectos.");
                });
    }

    @Operation(summary = "Salir: invalida el token al instante")
    @PostMapping("/logout")
    public void logout(@RequestBody(required = false) TokenRequest req) {
        if (req != null) sesiones.salir(req.token());
    }

    @Operation(summary = "Lista los usuarios del panel")
    @GetMapping("/usuarios")
    public List<Usuario> listar() {
        return usuarios.listar();
    }

    @Operation(summary = "Crea un usuario del panel")
    @PostMapping("/usuarios")
    public Usuario crear(@Valid @RequestBody NuevoUsuario nuevo) {
        if (usuarios.existe(nuevo.usuario())) {
            throw new ErrorDeGateway(CodigoDeError.USUARIO_YA_EXISTE,
                    "Ya hay un usuario que se llama " + nuevo.usuario() + ".");
        }
        usuarios.crear(nuevo.usuario(), nuevo.clave(), nuevo.nombre(), ContextoDeLlamada.usuario());
        return usuarios.listar().stream()
                .filter(u -> u.usuario().equals(nuevo.usuario()))
                .findFirst().orElseThrow();
    }

    @Operation(summary = "Cambia la contrasenia de un usuario",
            description = "Requiere la contrasenia actual para verificar la identidad.")
    @PostMapping("/usuarios/{usuario}/clave")
    public void cambiarClave(@PathVariable String usuario, @Valid @RequestBody ClaveRequest req) {
        if (!usuarios.existe(usuario)) {
            throw new ErrorDeGateway(CodigoDeError.USUARIO_INEXISTENTE,
                    "No hay un usuario que se llame " + usuario + ".");
        }
        if (usuarios.autenticar(usuario, req.claveActual()).isEmpty()) {
            throw new ErrorDeGateway(CodigoDeError.NO_AUTORIZADO,
                    "La contrasenia actual no es correcta.");
        }
        usuarios.cambiarClave(usuario, req.clave());
    }

    @Operation(summary = "Desactiva un usuario",
            description = "No se borra: las distribuidoras que dio de alta siguen apuntando a el.")
    @DeleteMapping("/usuarios/{usuario}")
    public void desactivar(@PathVariable String usuario) {
        if (usuario.equals(ContextoDeLlamada.usuario())) {
            throw new ErrorDeGateway(CodigoDeError.PEDIDO_INVALIDO,
                    "No te podes desactivar a vos mismo.");
        }
        usuarios.desactivar(usuario);
    }

    public record LoginRequest(@NotBlank String usuario, @NotBlank String clave) {}

    public record TokenRequest(String token) {}

    /** @param duracionMinutos cuanto dura la sesion sin actividad */
    public record Sesion(String token, String usuario, long duracionMinutos) {}

    public record NuevoUsuario(
            @NotBlank String usuario,
            @NotBlank @Size(min = 12, message = "al menos 12 caracteres") String clave,
            String nombre) {}

    public record ClaveRequest(
            @NotBlank String claveActual,
            @NotBlank @Size(min = 12, message = "al menos 12 caracteres") String clave) {}
}
