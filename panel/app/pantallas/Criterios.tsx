'use client';

import { useState } from 'react';
import { api } from '../../lib/api';
import type { Criterio, Criterios as Catalogo, Distribuidora, Modificador } from '../../lib/tipos';
import { Tarjeta, Aviso, exacto, porcentaje, useAccion } from '../componentes';

/**
 * Que bonificaciones tiene cargadas una distribuidora.
 *
 * Es la pantalla de "por que este cliente no tiene tal promo". El numero de un pedido NO sale de
 * aca: sale de la valorizacion, que la calcula el ERP. Esto es el catalogo, no el motor.
 *
 * Dos cosas que se muestran y normalmente se esconderian:
 *
 *  - los criterios NO VIGENTES, con el filtro. Sin eso no se puede contestar "andaba la semana
 *    pasada": lo mas probable es que la promo venciera.
 *  - los modificadores que NO SABEMOS interpretar, con su JSON crudo. Tragarselos haria que una
 *    promo desaparezca de la pantalla sin que nadie se entere.
 */
export default function Criterios(
  { distribuidoras, inicial }: { distribuidoras: Distribuidora[]; inicial?: string },
) {
  const a = useAccion();
  const [codigo, setCodigo] = useState(inicial ?? distribuidoras[0]?.codigo ?? '');
  const [incluirNoVigentes, setIncluirNoVigentes] = useState(false);
  const [filtro, setFiltro] = useState('');
  const [r, setR] = useState<Catalogo | null>(null);
  const [abierto, setAbierto] = useState<string | null>(null);

  const traer = (noVigentes = incluirNoVigentes) => a.correr(async () => {
    const c = await api.criterios(codigo, noVigentes);
    setR(c);
    return c;
  }, 'Catalogo traido.');

  const texto = filtro.trim().toLowerCase();
  const visibles = (r?.criterios ?? []).filter((c) => !texto
    || (c.nombre ?? '').toLowerCase().includes(texto)
    || c.id.includes(texto)
    || c.bonificaciones.some((b) => (b.descripcion ?? '').toLowerCase().includes(texto))
    || c.condiciones.some((x) => x.valores.some((v) => v.toLowerCase().includes(texto))));

  return (
    <>
      <Aviso tipo="error">{a.error}</Aviso>

      <Tarjeta
        titulo="Criterios de venta"
        ayuda="Las bonificaciones cargadas en GESCOM. Esto es el catalogo: el descuento de un pedido lo calcula el ERP y se ve en 'Probar una valorizacion'."
      >
        <div className="fila">
          <div>
            <label htmlFor="crit-distri">Distribuidora</label>
            <select id="crit-distri" value={codigo} onChange={(e) => setCodigo(e.target.value)}>
              {distribuidoras.map((d) => (
                <option key={d.codigo} value={d.codigo}>{d.codigo}</option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="crit-filtro">Buscar (nombre, id, articulo, marca...)</label>
            <input id="crit-filtro" value={filtro}
                   onChange={(e) => setFiltro(e.target.value)} />
          </div>
          <button className="accion" disabled={a.cargando || !codigo} onClick={() => traer()}>
            {a.cargando ? 'Trayendo...' : 'Traer'}
          </button>
        </div>
        <label style={{ marginTop: 12, display: 'flex', gap: 8, alignItems: 'center' }}>
          <input type="checkbox" checked={incluirNoVigentes} style={{ width: 'auto' }}
                 onChange={(e) => {
                   setIncluirNoVigentes(e.target.checked);
                   if (r) void traer(e.target.checked);
                 }} />
          Incluir vencidos e inactivos (para contestar &quot;andaba la semana pasada&quot;)
        </label>
      </Tarjeta>

      {r && (
        <Tarjeta titulo={`${visibles.length} de ${r.total} criterios en ${r.tenant}`}>
          {r.total === 0 && (
            <p className="tenue">
              Esta distribuidora no tiene criterios vigentes. Proba con el filtro de vencidos.
            </p>
          )}
          {visibles.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th>Id</th><th>Nombre</th><th>Vigencia</th><th>Que hace</th><th />
                </tr>
              </thead>
              <tbody>
                {visibles.map((c) => (
                  <tr key={c.id} className="clicable"
                      data-activa={abierto === c.id}
                      onClick={() => setAbierto(abierto === c.id ? null : c.id)}>
                    <td className="mono">{c.id}</td>
                    <td style={{ whiteSpace: 'normal' }}>{c.nombre ?? '—'}</td>
                    <td><Vigencia c={c} /></td>
                    <td style={{ whiteSpace: 'normal' }}>
                      {c.bonificaciones.map((b, i) => <Que key={i} b={b} />)}
                    </td>
                    <td className="tenue">{abierto === c.id ? 'ocultar' : 'ver por que'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Tarjeta>
      )}

      {abierto && r && (() => {
        const c = r.criterios.find((x) => x.id === abierto);
        if (!c) return null;
        return (
          <Tarjeta titulo={`${c.id} — ${c.nombre ?? 'sin nombre'}`}
                   ayuda="Las condiciones que tienen que cumplirse para que aplique. Solo las que participan de la evaluacion: el ERP trae condiciones huerfanas que no se alcanzan nunca y esas no se muestran.">
            {c.descripcion && <p>{c.descripcion}</p>}
            {c.clientes.length > 0 && (
              <p className="tenue">
                Clientes: <span className="mono">{c.clientes.join(', ')}</span>
              </p>
            )}
            {c.condiciones.length === 0
              ? <p className="tenue">Sin condiciones: cae sobre todo lo que califique.</p>
              : (
                <table>
                  <thead>
                    <tr><th>Condicion</th><th>Valores</th><th>Cant. min.</th><th /></tr>
                  </thead>
                  <tbody>
                    {c.condiciones.map((x, i) => (
                      <tr key={i}>
                        <td style={{ whiteSpace: 'normal' }}>{x.descripcion ?? x.tipo}</td>
                        <td className="mono" style={{ whiteSpace: 'normal' }}>
                          {x.valores.join(', ') || '—'}
                        </td>
                        <td className="num">{x.cantidadMinima ?? '—'}</td>
                        <td>
                          {x.invertida && <span className="pill warn">invertida</span>}
                          {x.tipo === 'DESCONOCIDA' && (
                            <span className="pill error" title="GESCOM trajo un tipo de condicion que no sabemos interpretar">
                              sin interpretar
                            </span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
          </Tarjeta>
        );
      })()}
    </>
  );
}

function Vigencia({ c }: { c: Criterio }) {
  if (!c.activo) return <span className="pill error">inactiva</span>;
  if (!c.vigenteDesde && !c.vigenteHasta) return <span className="pill ok">sin limite</span>;
  return (
    <span className="pill tenue">
      {c.vigenteDesde ?? '—'} a {c.vigenteHasta ?? 'sin fin'}
    </span>
  );
}

/** Que hace el modificador, en una linea. Los no reconocidos se muestran, no se esconden. */
function Que({ b }: { b: Modificador }) {
  if (b.tipo === 'DESCUENTO') {
    return (
      <div>
        {porcentaje(b.descuento)} de descuento
        {b.tope !== null && <span className="tenue"> (tope {exacto(b.tope)})</span>}
      </div>
    );
  }
  if (b.tipo === 'ESCALA') {
    return (
      <div>
        Escala:{' '}
        {(b.tramos ?? []).map((t) => `desde ${exacto(t.desdeCantidad)} → ${porcentaje(t.descuento)}`)
          .join(' · ')}
      </div>
    );
  }
  if (b.tipo === 'ITEM_SIN_CARGO') {
    return (
      <div>
        Regala <span className="mono">{b.codigoItem}</span> x{exacto(b.cantidad)}
      </div>
    );
  }
  return (
    <div>
      <span className="pill error">sin interpretar</span>{' '}
      <span className="tenue">
        GESCOM lo llama <span className="mono">{b.tipoEnElErp}</span>. El descuento del pedido
        igual sale bien (lo calcula el ERP); lo que no podemos es explicarlo aca.
      </span>
    </div>
  );
}
