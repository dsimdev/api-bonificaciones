'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, sesion } from '../../lib/api';
import type { Usuario } from '../../lib/tipos';
import { Tarjeta, Aviso, useAccion } from '../componentes';

/**
 * Quien puede entrar al panel.
 *
 * Son usuarios con nombre y no una clave compartida porque lo que importa es la AUDITORIA: cada
 * distribuidora guarda quien la dio de alta y quien la toco por ultima vez. Una clave compartida
 * no contesta "quien cargo esta credencial" ni se puede rotar cuando alguien se va.
 *
 * Desactivar no borra: las distribuidoras que esa persona dio de alta siguen apuntando a ella.
 */
export default function Usuarios() {
  const [lista, setLista] = useState<Usuario[] | null>(null);

  const traer = useCallback(async () => {
    setLista(await api.usuarios());
  }, []);

  const a = useAccion(traer);

  // eslint-disable-next-line react-hooks/exhaustive-deps -- carga una vez al montar, a proposito
  useEffect(() => { void a.correr(traer, 'Listado.'); }, []);

  const [n, setN] = useState({ usuario: '', nombre: '', clave: '' });

  const crear = () => a.correr(async () => {
    const r = await api.crearUsuario(n.usuario.trim(), n.clave, n.nombre.trim());
    setN({ usuario: '', nombre: '', clave: '' });
    return r;
  }, 'Usuario creado.');

  const cambiarClave = (usuario: string) => {
    const claveActual = prompt(`Contrasenia actual de ${usuario}:`);
    if (!claveActual) return;
    const clave = prompt(`Contrasenia nueva para ${usuario} (minimo 12 caracteres):`);
    if (!clave) return;
    void a.correr(() => api.cambiarClave(usuario, claveActual, clave), 'Contrasenia cambiada.',
      { recargar: false });
  };

  const desactivar = (usuario: string) => {
    if (!confirm(`Desactivar a ${usuario}? No se borra: deja de poder entrar.`)) return;
    void a.correr(() => api.desactivarUsuario(usuario), 'Usuario desactivado.');
  };

  return (
    <>
      <Aviso tipo="error">{a.error}</Aviso>
      <Aviso tipo="ok">{a.ok}</Aviso>

      <Tarjeta
        titulo="Nuevo usuario"
        ayuda="Para que cada uno entre con el suyo: asi queda registrado quien dio de alta cada distribuidora."
      >
        <div className="fila" style={{ marginBottom: 12 }}>
          <div>
            <label htmlFor="u-usuario">Usuario</label>
            <input id="u-usuario" value={n.usuario} autoComplete="off"
                   onChange={(e) => setN({ ...n, usuario: e.target.value })} />
          </div>
          <div>
            <label htmlFor="u-nombre">Nombre</label>
            <input id="u-nombre" value={n.nombre}
                   onChange={(e) => setN({ ...n, nombre: e.target.value })} />
          </div>
          <div>
            <label htmlFor="u-clave">Contrasenia (minimo 12)</label>
            <input id="u-clave" type="password" value={n.clave} autoComplete="new-password"
                   onChange={(e) => setN({ ...n, clave: e.target.value })} />
          </div>
        </div>
        <button className="accion" onClick={crear}
                disabled={a.cargando || !n.usuario.trim() || n.clave.length < 12}>
          Crear
        </button>
      </Tarjeta>

      <Tarjeta titulo={lista ? lista.length + ' usuarios' : 'Usuarios'}>
        {lista && (
          <table>
            <thead>
              <tr><th>Usuario</th><th>Nombre</th><th>Estado</th><th /></tr>
            </thead>
            <tbody>
              {lista.map((u) => (
                <tr key={u.usuario} data-anulada={!u.activo}>
                  <td className="mono">
                    {u.usuario}
                    {u.usuario === sesion.usuario && (
                      <span className="pill tenue" style={{ marginLeft: 6 }}>vos</span>
                    )}
                  </td>
                  <td>{u.nombre ?? '—'}</td>
                  <td>
                    {u.activo
                      ? <span className="pill ok">activo</span>
                      : <span className="pill error">desactivado</span>}
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end' }}>
                      <button className="accion secundaria" disabled={a.cargando}
                              onClick={() => cambiarClave(u.usuario)}>
                        Cambiar contrasenia
                      </button>
                      <button className="accion peligro" disabled={a.cargando || !u.activo
                                || u.usuario === sesion.usuario}
                              onClick={() => desactivar(u.usuario)}>
                        Desactivar
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Tarjeta>
    </>
  );
}
