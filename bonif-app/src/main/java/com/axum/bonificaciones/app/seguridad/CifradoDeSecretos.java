package com.axum.bonificaciones.app.seguridad;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifrado reversible para las claves de API de GESCOM de cada distribuidora.
 *
 * Son claves AJENAS y hay que poder leerlas en claro para mintear el token cada 5 minutos, asi que
 * se cifran, no se hashean. Lo contrario de nuestras propias api-keys, que solo hay que comparar.
 *
 * AES-256-GCM: autenticado, no solo confidencial -- si el valor guardado fue alterado, descifrar
 * falla en vez de devolver basura en silencio.
 *
 * Mismo enfoque que CifradoDeSecretos de api-impuestos, a proposito: la instalacion y la operacion
 * de los dos servicios tienen que parecerse para quien administra el servidor.
 */
@Component
public class CifradoDeSecretos {

    private static final int TAMANIO_IV = 12;
    private static final int TAMANIO_TAG_BITS = 128;

    private final byte[] clave;

    /**
     * Sin CIFRADO_KEY configurada, guardar o leer una clave de GESCOM queda deshabilitado: es
     * preferible fallar explicito a que un despliegue distraido guarde mil claves de produccion
     * sin cifrar de verdad. Generarla con {@code openssl rand -hex 32}.
     */
    public CifradoDeSecretos(@Value("${bonificaciones.cifrado-key:}") String claveHex) {
        if (claveHex == null || claveHex.isBlank()) {
            this.clave = null;
            return;
        }
        var bytes = HexFormat.of().parseHex(claveHex.trim());
        if (bytes.length != 32) {
            throw new IllegalStateException(
                    "CIFRADO_KEY tiene que ser de 32 bytes (64 caracteres hexa) para AES-256. "
                            + "Generala con: openssl rand -hex 32");
        }
        this.clave = bytes;
    }

    public boolean habilitado() {
        return clave != null;
    }

    private void exigirHabilitado() {
        if (clave == null) {
            throw new CifradoDeshabilitadoException(
                    "No hay CIFRADO_KEY configurada: no se pueden guardar ni leer las claves de "
                            + "GESCOM. Generala con 'openssl rand -hex 32' y ponela en el entorno.");
        }
    }

    public String cifrar(String claro) {
        exigirHabilitado();
        try {
            var iv = new byte[TAMANIO_IV];
            new SecureRandom().nextBytes(iv);

            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(clave, "AES"),
                    new GCMParameterSpec(TAMANIO_TAG_BITS, iv));
            var cifrado = cipher.doFinal(claro.getBytes(StandardCharsets.UTF_8));

            // El IV va adelante del texto cifrado: no es secreto, pero tiene que viajar con el.
            var salida = new byte[iv.length + cifrado.length];
            System.arraycopy(iv, 0, salida, 0, iv.length);
            System.arraycopy(cifrado, 0, salida, iv.length, cifrado.length);
            return Base64.getEncoder().encodeToString(salida);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar el secreto", e);
        }
    }

    public String descifrar(String guardado) {
        exigirHabilitado();
        try {
            var bytes = Base64.getDecoder().decode(guardado);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(clave, "AES"),
                    new GCMParameterSpec(TAMANIO_TAG_BITS, bytes, 0, TAMANIO_IV));
            var claro = cipher.doFinal(bytes, TAMANIO_IV, bytes.length - TAMANIO_IV);
            return new String(claro, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Pasa si cambio la CIFRADO_KEY o si alguien toco la fila en la base. Las dos cosas
            // hay que saberlas, no descubrirlas como un "usuario o clave invalidos" del ERP.
            throw new IllegalStateException(
                    "No se pudo descifrar el secreto: la CIFRADO_KEY no es la que lo cifro, o el "
                            + "valor guardado fue alterado", e);
        }
    }

    public static class CifradoDeshabilitadoException extends RuntimeException {
        public CifradoDeshabilitadoException(String mensaje) {
            super(mensaje);
        }
    }
}
