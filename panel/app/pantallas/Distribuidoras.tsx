'use client';

import { useState } from 'react';
import { api } from '../../lib/api';
import type { Creada, Distribuidora } from '../../lib/tipos';
import { Tarjeta, Aviso, cuando, useAccion } from '../componentes';
import ActividadCard from './ActividadCard';

/**
 * Alta de distribuidoras y estado de las que ya estan.
 *
 * **El alta valida contra GESCOM antes de guardar** (mintea el token y trae el catalogo). No es un
 * extra: esto lo usa alguien sin contexto, copiando de una coleccion de Postman, hasta diez veces
 * en un dia. Si una clave se copia mal y nadie la prueba, queda una distribuidora rota que nadie
 * descubre hasta que falla el checkout de esa tienda, probablemente delante de un cliente.
 *
 * Los campos se llaman como en Postman a proposito: copiar tiene que ser copiar, no interpretar.
 */
export default function Distribuidoras(
  { lista, recargar, alProbar }:
    { lista: Distribuidora[] | null; recargar: () => Promise<void>;
      alProbar: (codigo: string) => void },
) {
  const [creada, setCreada] = useState<Creada | null>(null);
  const [estados, setEstados] = useState<Record<string, string>>({});

  // La lista la tiene el padre: el selector de "Probar" usa la misma, asi que una distribuidora
  // recien dada de alta aparece en las dos sin pedirla dos veces.
  const a = useAccion(recargar);

  const vacio = { codigo: '', nombre: '', usuario: '', clave: '', host: '', realm: '' };
  const [n, setN] = useState(vacio);

  const crear = () => a.correr(async () => {
    const r = await api.crear({
      codigo: n.codigo.trim(),
      nombre: n.nombre.trim() || undefined,
      usuario: n.usuario.trim(),
      clave: n.clave,
      host: n.host.trim() || undefined,
      realm: n.realm.trim() || undefined,
    });
    setCreada(r);
    setN(vacio);
    return r;
  }, 'Distribuidora dada de alta.');

  // Vuelve a probar la credencial YA guardada: "el alta que hice ayer, anda hoy?". Una clave que
  // rotaron del lado del ERP se descubre aca y no en un checkout.
  const verificar = (codigo: string) => a.correr(async () => {
    setEstados((e) => ({ ...e, [codigo]: 'probando...' }));
    try {
      const r = await api.verificar(codigo);
      setEstados((e) => ({ ...e, [codigo]: 'anda, ' + r.criterios + ' criterios' }));
      return r;
    } catch (err) {
      setEstados((e) => ({ ...e, [codigo]: 'NO anda' }));
      throw err;
    }
  }, 'Credencial probada.', { recargar: false });

  const regenerar = (codigo: string) => {
    const aviso = 'Regenerar la clave de ' + codigo + '?\n\nLa actual queda revocada al instante '
      + 'y la tienda deja de funcionar hasta que la cambie.';
    if (!confirm(aviso)) return;
    void a.correr(async () => {
      const r = await api.regenerarClaveDeTienda(codigo);
      setCreada(r);
      return r;
    }, 'Clave nueva generada.', { recargar: false });
  };

  const pillDe = (estado: string): string => {
    if (estado.startsWith('anda')) return 'ok';
    if (estado === 'NO anda') return 'error';
    return 'tenue';
  };

  return (
    <>
      <Aviso tipo="error">{a.error}</Aviso>
      <Aviso tipo="ok">{a.ok}</Aviso>

      {/* Arriba de todo: "la tienda dice que no funciona" empieza por saber si las llamadas
          estan llegando. Se oculta sola si todavia no hubo ninguna. */}
      <ActividadCard />

      {creada && (
        <Tarjeta titulo={'Clave de la tienda — ' + creada.codigo}>
          <Aviso tipo="warn">{creada.mensaje}</Aviso>
          <p className="etiqueta">Esta clave va en el header x-api-key de la tienda</p>
          <input readOnly value={creada.claveDeLaTienda} className="mono"
                 onFocus={(e) => e.currentTarget.select()} />
          <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
            <button className="accion secundaria"
                    onClick={() => void navigator.clipboard.writeText(creada.claveDeLaTienda)}>
              Copiar
            </button>
            <button className="accion secundaria" onClick={() => setCreada(null)}>
              Ya la guarde
            </button>
          </div>
        </Tarjeta>
      )}

      <Tarjeta
        titulo="Nueva distribuidora"
        ayuda="Copia los tres datos de la coleccion de Postman de la distribuidora. Al guardar se prueba la credencial contra GESCOM: si no anda, no se guarda nada."
      >
        <div className="fila" style={{ marginBottom: 12 }}>
          <div>
            <label htmlFor="codigo">DISTRIBUIDORA (va en la URL)</label>
            <input id="codigo" value={n.codigo} placeholder="dyssa"
                   onChange={(e) => setN({ ...n, codigo: e.target.value })} />
          </div>
          <div>
            <label htmlFor="nombre">Nombre (opcional, solo para verlo aca)</label>
            <input id="nombre" value={n.nombre}
                   onChange={(e) => setN({ ...n, nombre: e.target.value })} />
          </div>
        </div>
        <div className="fila" style={{ marginBottom: 12 }}>
          <div>
            <label htmlFor="usuario">USERNAME</label>
            <input id="usuario" value={n.usuario} autoComplete="off"
                   onChange={(e) => setN({ ...n, usuario: e.target.value })} />
          </div>
          <div>
            <label htmlFor="clave">PASSWORD</label>
            <input id="clave" type="password" value={n.clave} autoComplete="new-password"
                   placeholder="se guarda cifrada"
                   onChange={(e) => setN({ ...n, clave: e.target.value })} />
          </div>
        </div>

        {/* El host y el realm salen del codigo por convencion, verificado en dyssa y senderolaser.
            Van escondidos y vacios porque lo normal es no tocarlos -- pero con mil distribuidoras
            alguna no va a seguir la convencion, y para esa no queremos mandar a nadie a Swagger. */}
        <details style={{ marginBottom: 14 }}>
          <summary className="tenue" style={{ cursor: 'pointer', fontSize: 13 }}>
            Esta distribuidora no sigue la convencion de URL
          </summary>
          <p className="ayuda" style={{ marginTop: 10 }}>
            Dejalos vacios salvo que sepas que esta distribuidora es distinta. Por defecto{' '}
            <span className="mono">https://{n.codigo || 'codigo'}.gescom.online</span> y{' '}
            <span className="mono">gcw-{n.codigo || 'codigo'}</span>.
          </p>
          <div className="fila">
            <div>
              <label htmlFor="host">Host</label>
              <input id="host" value={n.host}
                     onChange={(e) => setN({ ...n, host: e.target.value })} />
            </div>
            <div>
              <label htmlFor="realm">Realm de Keycloak</label>
              <input id="realm" value={n.realm}
                     onChange={(e) => setN({ ...n, realm: e.target.value })} />
            </div>
          </div>
        </details>

        <button className="accion" onClick={crear}
                disabled={a.cargando || !n.codigo.trim() || !n.usuario.trim() || !n.clave}>
          {a.cargando ? 'Probando contra GESCOM...' : 'Probar y dar de alta'}
        </button>
      </Tarjeta>

      <Tarjeta titulo={lista ? lista.length + ' distribuidoras' : 'Distribuidoras'}>
        {lista && lista.length === 0 && (
          <p className="tenue">Todavia no hay ninguna. Cargala arriba.</p>
        )}
        {lista && lista.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Codigo</th><th>Nombre</th><th>Usuario de GESCOM</th><th>Alta</th>
                <th>Estado</th><th />
              </tr>
            </thead>
            <tbody>
              {lista.map((d) => (
                <tr key={d.codigo} data-anulada={!d.activa}>
                  <td className="mono">
                    {d.codigo}
                    {d.fueraDeConvencion && (
                      <span className="pill warn" style={{ marginLeft: 6 }}
                            title={d.host + ' / ' + d.realm}>URL propia</span>
                    )}
                  </td>
                  <td>{d.nombre ?? '—'}</td>
                  <td className="mono">{d.usuario}</td>
                  <td className="tenue">
                    {cuando(d.creadaEn)}{d.creadaPor ? ' · ' + d.creadaPor : ''}
                  </td>
                  <td>
                    {estados[d.codigo]
                      ? (
                        <span className={'pill ' + pillDe(estados[d.codigo])}>
                          {estados[d.codigo]}
                        </span>
                      )
                      : <span className="tenue">sin probar</span>}
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end' }}>
                      <button className="accion secundaria" disabled={a.cargando}
                              onClick={() => void verificar(d.codigo)}>
                        Probar credencial
                      </button>
                      <button className="accion secundaria" onClick={() => alProbar(d.codigo)}>
                        Valorizar
                      </button>
                      <button className="accion secundaria" disabled={a.cargando}
                              onClick={() => regenerar(d.codigo)}>
                        Nueva clave
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
