package com.axum.bonificaciones.core.model;

/**
 * De donde sale la informacion.
 *
 * Hoy solo GESCOM. El gateway de Axum se evaluo como fuente y quedo FUERA DE ALCANCE el
 * 2026-10-07: la tienda consume las bonificaciones de Axum directamente, sin pasar por aca
 * (ver docs/proyecto/fuente-axum-bonificaciones.md, que se conserva como referencia).
 *
 * El enum existe igual, con un solo valor, porque la respuesta declara su fuente y porque Chess
 * puede entrar mas adelante. No se agregan valores para fuentes que todavia no se implementan.
 */
public enum Fuente {
    GESCOM
}
