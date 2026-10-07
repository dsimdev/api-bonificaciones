'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, sesion } from '../lib/api';
import type { Distribuidora } from '../lib/tipos';
import Acceso from './pantallas/Acceso';
import Distribuidoras from './pantallas/Distribuidoras';
import Probar from './pantallas/Probar';
import Usuarios from './pantallas/Usuarios';

const TABS = [
  ['distribuidoras', 'Distribuidoras'],
  ['probar', 'Probar una valorizacion'],
  ['usuarios', 'Usuarios'],
] as const;

export default function Panel() {
  // La sesion vive en sessionStorage, que no existe en el server. Se resuelve despues del primer
  // render para que el export estatico no intente leerlo al generar el HTML.
  const [listo, setListo] = useState(false);
  const [dentro, setDentro] = useState(false);
  const [activa, setActiva] = useState<string>('distribuidoras');

  // Se cargan una vez y se comparten: el selector de "Probar" las necesita, y no tiene sentido
  // que cada pantalla pida la misma lista.
  const [distribuidoras, setDistribuidoras] = useState<Distribuidora[] | null>(null);
  const [paraProbar, setParaProbar] = useState<string | undefined>(undefined);

  const traerDistribuidoras = useCallback(async () => {
    setDistribuidoras(await api.distribuidoras());
  }, []);

  useEffect(() => {
    setDentro(sesion.dentro);
    setListo(true);
  }, []);

  useEffect(() => {
    // El error se muestra en la pantalla de Distribuidoras, que lo pide de nuevo con useAccion.
    if (dentro) void traerDistribuidoras().catch(() => undefined);
  }, [dentro, traerDistribuidoras]);

  if (!listo) return null;
  if (!dentro) return <Acceso alEntrar={() => setDentro(true)} />;

  const salir = () => {
    // Se invalida del lado del servidor, no solo en el navegador: un token que sigue vivo despues
    // de "Salir" es un token que alguien puede usar.
    void api.salir().catch(() => undefined);
    sesion.limpiar();
    setDentro(false);
  };

  const irAProbar = (codigo: string) => {
    setParaProbar(codigo);
    setActiva('probar');
  };

  return (
    <>
      <header className="barra">
        <div className="contenedor">
          <strong>Bonificaciones</strong>
          <span className="tenant">{sesion.usuario}</span>
          <span className="sep" />
          <button className="accion secundaria" onClick={salir}>Salir</button>
        </div>
      </header>

      <div className="contenedor">
        <nav className="tabs">
          {TABS.map(([id, etiqueta]) => (
            <button key={id} data-activa={activa === id} onClick={() => setActiva(id)}>
              {etiqueta}
            </button>
          ))}
        </nav>

        {activa === 'distribuidoras' && (
          <Distribuidoras lista={distribuidoras} recargar={traerDistribuidoras}
                          alProbar={irAProbar} />
        )}
        {activa === 'probar' && (
          <Probar distribuidoras={distribuidoras ?? []} inicial={paraProbar} />
        )}
        {activa === 'usuarios' && <Usuarios />}
      </div>
    </>
  );
}
