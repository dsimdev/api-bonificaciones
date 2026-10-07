package com.axum.bonificaciones.app.seguridad;

/** Un usuario del panel. La contrasenia nunca sale del repositorio. */
public record Usuario(long id, String usuario, String nombre, boolean activo) {}
