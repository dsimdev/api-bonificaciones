package com.axum.bonificaciones.core.model;

import java.util.List;

/**
 * Un criterio de venta normalizado: lo que el negocio llama "bonificacion" o "promocion".
 *
 * En GESCOM no hay endpoint de bonificaciones: esto es una entrada de get-promociones, con su
 * configuracionJson ya parseado. El modelo es el mismo para cualquier ERP que se sume despues.
 */
public record Criterio(
        Erp erp,
        String distribuidora,
        String id,
        String nombre,
        boolean activo,
        Vigencia vigencia,
        List<Condicion> condiciones,
        List<Modificador> modificadores,
        List<String> clientes) {

    public Criterio {
        condiciones = condiciones == null ? List.of() : List.copyOf(condiciones);
        modificadores = modificadores == null ? List.of() : List.copyOf(modificadores);
        clientes = clientes == null ? List.of() : List.copyOf(clientes);
    }

    /** Vacio = el criterio no esta limitado a clientes puntuales (lo deciden las condiciones). */
    public boolean limitadoAClientes() {
        return !clientes.isEmpty();
    }
}
