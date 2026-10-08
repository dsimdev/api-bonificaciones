'use client';

import { useCallback, useEffect, useState } from 'react';
import { api } from '../../lib/api';
import type { Actividad } from '../../lib/tipos';
import { Tarjeta } from '../componentes';

/**
 * Cuántas llamadas hizo cada tienda y cómo le salieron.
 *
 * Va arriba de las distribuidoras porque es lo que contesta la pregunta con la que suele empezar
 * todo: *"la tienda dice que no funciona"*. Primero se mira si las llamadas están llegando y con
 * qué código fallan; recién después se abre el diagnóstico.
 *
 * Son contadores en memoria desde que arrancó el servicio. No es auditoría: para "qué pasó ayer a
 * las tres" está el log del servidor, que se rota por día.
 */
export default function ActividadCard() {
  const [lista, setLista] = useState<Actividad[] | null>(null);
  const [error, setError] = useState(false);

  const traer = useCallback(async () => {
    try {
      setLista(await api.actividad());
      setError(false);
    } catch {
      // Es informativo: si falla, no vale tapar la pantalla de alta con un error.
      setError(true);
    }
  }, []);

  useEffect(() => { void traer(); }, [traer]);

  if (error || !lista || lista.length === 0) return null;

  return (
    <Tarjeta
      titulo="Actividad"
      ayuda="Llamadas de las tiendas desde que arrancó el servicio. Si una distribuidora no aparece, todavía no nos llamó: ahí el problema no es nuestro."
    >
      <table>
        <thead>
          <tr>
            <th>Distribuidora</th><th className="num">Llamadas</th><th className="num">Fallaron</th>
            <th className="num">Promedio</th><th>Con qué error</th>
          </tr>
        </thead>
        <tbody>
          {lista.map((x) => (
            <tr key={x.tenant}>
              <td className="mono">{x.tenant}</td>
              <td className="num">{x.total}</td>
              <td className="num">
                {x.fallidas === 0
                  ? <span className="tenue">0</span>
                  : <span className="pill error">{x.fallidas}</span>}
              </td>
              <td className="num">{x.msPromedio} ms</td>
              <td style={{ whiteSpace: 'normal' }}>
                {Object.keys(x.porCodigo).length === 0
                  ? <span className="tenue">—</span>
                  : Object.entries(x.porCodigo)
                    .sort((a, b) => b[1] - a[1])
                    .map(([codigo, veces]) => (
                      <span key={codigo} className="pill warn" style={{ marginRight: 6 }}>
                        {codigo} ×{veces}
                      </span>
                    ))}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <button className="accion secundaria" style={{ marginTop: 12 }}
              onClick={() => void traer()}>
        Actualizar
      </button>
    </Tarjeta>
  );
}
