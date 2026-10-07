'use client';

import { useState } from 'react';
import { api, sesion } from '../../lib/api';
import { Tarjeta, Aviso } from '../componentes';

/**
 * Login con usuario y contrasenia, no una clave compartida.
 *
 * El motivo no es el login: es la AUDITORIA. Con mil distribuidoras cargadas por varias personas,
 * "quien dio de alta esta credencial" es una pregunta que se va a hacer -- y una clave compartida
 * no la contesta ni se puede rotar cuando alguien se va.
 */
export default function Acceso({ alEntrar }: { alEntrar: () => void }) {
  const [usuario, setUsuario] = useState('');
  const [clave, setClave] = useState('');
  const [error, setError] = useState('');
  const [cargando, setCargando] = useState(false);

  const entrar = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!usuario.trim() || !clave) {
      setError('Hacen falta el usuario y la contrasenia.');
      return;
    }
    setError('');
    setCargando(true);
    try {
      sesion.guardar(await api.login(usuario.trim(), clave));
      alEntrar();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setCargando(false);
    }
  };

  return (
    <div className="contenedor" style={{ maxWidth: 420, paddingTop: 80 }}>
      <Tarjeta titulo="Bonificaciones — Panel">
        <Aviso tipo="error">{error}</Aviso>
        <form onSubmit={entrar}>
          <div style={{ marginBottom: 14 }}>
            <label htmlFor="usuario">Usuario</label>
            <input id="usuario" value={usuario} onChange={(e) => setUsuario(e.target.value)}
                   autoComplete="username" autoFocus />
          </div>
          <div style={{ marginBottom: 18 }}>
            <label htmlFor="clave">Contrasenia</label>
            <input id="clave" type="password" value={clave}
                   onChange={(e) => setClave(e.target.value)} autoComplete="current-password" />
          </div>
          <button className="accion" type="submit" style={{ width: '100%' }} disabled={cargando}>
            {cargando ? 'Entrando...' : 'Entrar'}
          </button>
        </form>
        <p className="ayuda" style={{ marginTop: 16, marginBottom: 0 }}>
          La sesion queda en <span className="mono">sessionStorage</span> y se borra al cerrar la
          pestania.
        </p>
      </Tarjeta>
    </div>
  );
}
