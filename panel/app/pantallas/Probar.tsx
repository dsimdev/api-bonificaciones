'use client';

import { useState } from 'react';
import { api } from '../../lib/api';
import type { Diagnostico, Distribuidora } from '../../lib/tipos';
import { Tarjeta, Aviso, exacto, pesos, porcentaje, useAccion } from '../componentes';

/**
 * Probar una valorizacion, viendo las tres capas a la vez.
 *
 * **Para que es.** Cuando una tienda dice "el descuento esta mal", hay tres sospechosos y desde
 * afuera son indistinguibles: el ERP (que es el que calcula), nuestra normalizacion, o lo que la
 * tienda muestra en pantalla. Esta pantalla los separa:
 *
 *  - si `coincide` da false en la comparacion, el bug es NUESTRO;
 *  - si el ERP ya devolvio un numero raro, se ve en el crudo;
 *  - si todo cierra y la tienda muestra otra cosa, el problema es de la tienda.
 *
 * Hace el mismo pedido y recorre el mismo camino que la tienda. Es solo lectura: eval-pedido es
 * dry-run y no crea ni confirma nada.
 */
export default function Probar(
  { distribuidoras, inicial }: { distribuidoras: Distribuidora[]; inicial?: string },
) {
  const a = useAccion();
  const [codigo, setCodigo] = useState(inicial ?? distribuidoras[0]?.codigo ?? '');
  const [cliente, setCliente] = useState('');
  const [listaPrecio, setListaPrecio] = useState('');
  const [items, setItems] = useState([{ codigo: '', cantidad: '1' }]);
  const [r, setR] = useState<Diagnostico | null>(null);

  const cambiarItem = (i: number, campo: 'codigo' | 'cantidad', valor: string) => {
    setItems(items.map((it, j) => (i === j ? { ...it, [campo]: valor } : it)));
  };

  const valorizar = () => a.correr(async () => {
    setR(null);
    const d = await api.diagnosticar(codigo, {
      cliente: cliente.trim(),
      listaPrecio: listaPrecio.trim() || undefined,
      referencia: 'panel',
      items: items
        .filter((it) => it.codigo.trim())
        .map((it) => ({ codigo: it.codigo.trim(), cantidad: Number(it.cantidad) })),
    });
    setR(d);
    return d;
  }, 'Valorizado.');

  const listo = codigo && cliente.trim() && items.some((it) => it.codigo.trim());
  const hayBug = r ? r.comparacion.some((c) => !c.coincide) : false;

  return (
    <>
      <Aviso tipo="error">{a.error}</Aviso>

      <Tarjeta
        titulo="Probar una valorizacion"
        ayuda="Manda el mismo pedido que manda la tienda y muestra las tres capas: lo que le pedimos al ERP, lo que el ERP contesto crudo y lo que devolvemos nosotros. Es solo lectura: no crea ni confirma ningun pedido."
      >
        <div className="fila" style={{ marginBottom: 12 }}>
          <div>
            <label htmlFor="distri">Distribuidora</label>
            <select id="distri" value={codigo} onChange={(e) => setCodigo(e.target.value)}>
              {distribuidoras.map((d) => (
                <option key={d.codigo} value={d.codigo}>{d.codigo}</option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="cliente">Codigo de cliente (el del ERP)</label>
            <input id="cliente" value={cliente} placeholder="8380"
                   onChange={(e) => setCliente(e.target.value)} />
          </div>
          <div>
            <label htmlFor="lista">Lista de precio</label>
            <input id="lista" value={listaPrecio} placeholder="2"
                   onChange={(e) => setListaPrecio(e.target.value)} />
          </div>
        </div>

        {/* La lista de precio es el error mas probable y el mas dificil de ver: cambia el PRECIO
            pero no el porcentaje de descuento, asi que mirando el descuento no se nota. */}
        <Aviso tipo="warn">
          Manda la <strong>misma lista de precio</strong> que uso la tienda para mostrar el precio.
          La lista cambia el importe pero <strong>no</strong> el porcentaje de descuento: si esta
          mal, el descuento sale bien sobre un precio equivocado y no se ve mirando el descuento.
        </Aviso>

        <p className="etiqueta">Items del carrito</p>
        {items.map((it, i) => (
          <div className="fila" key={i} style={{ marginBottom: 8 }}>
            <div style={{ flex: 3 }}>
              <input value={it.codigo} placeholder="codigo de articulo"
                     onChange={(e) => cambiarItem(i, 'codigo', e.target.value)} />
            </div>
            <div style={{ flex: 1 }}>
              <input value={it.cantidad} type="number" min="0" step="any"
                     onChange={(e) => cambiarItem(i, 'cantidad', e.target.value)} />
            </div>
            <button className="accion secundaria" disabled={items.length === 1}
                    onClick={() => setItems(items.filter((_, j) => j !== i))}>
              Quitar
            </button>
          </div>
        ))}
        <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
          <button className="accion secundaria"
                  onClick={() => setItems([...items, { codigo: '', cantidad: '1' }])}>
            Agregar item
          </button>
          <button className="accion" disabled={a.cargando || !listo} onClick={valorizar}>
            {a.cargando ? 'Consultando al ERP...' : 'Valorizar'}
          </button>
        </div>
        <p className="ayuda" style={{ marginTop: 12, marginBottom: 0 }}>
          Manda el carrito <strong>entero</strong>, igual que la tienda. Los criterios se evaluan
          sobre toda la venta: preguntar item por item da descuentos distintos.
        </p>
      </Tarjeta>

      {r && (
        <>
          <Tarjeta titulo="Veredicto">
            <Aviso tipo={hayBug || r.lineasQueNoCierran.length > 0 ? 'error' : 'ok'}>
              {r.veredicto}
            </Aviso>
            {r.nuestraRespuesta.supuestos.length > 0 && (
              <Aviso tipo="warn">
                {r.nuestraRespuesta.supuestos.map((s) => s.mensaje).join(' · ')}
              </Aviso>
            )}
            <table>
              <tbody>
                <tr><td>Lo calculo</td><td className="mono">{r.nuestraRespuesta.calculadoPor}</td></tr>
                <tr><td>Neto</td><td className="num">{pesos(r.nuestraRespuesta.totales.neto)}</td></tr>
                <tr><td>Descuento (en pesos)</td><td className="num">{pesos(r.nuestraRespuesta.totales.descuento)}</td></tr>
                <tr><td><strong>Neto con descuento</strong></td><td className="num"><strong>{pesos(r.nuestraRespuesta.totales.netoConDescuento)}</strong></td></tr>
              </tbody>
            </table>
          </Tarjeta>

          <Tarjeta
            titulo="El ERP contra nosotros"
            ayuda="El descuento del ERP es una fraccion (0.1) y el nuestro un porcentaje (10): la relacion tiene que ser exactamente x100. Si una fila dice NO, el bug es nuestro."
          >
            <table>
              <thead>
                <tr>
                  <th>Item</th>
                  <th className="num">Desc. ERP</th><th className="num">Desc. nuestro</th>
                  <th className="num">Neto ERP</th><th className="num">Neto nuestro</th>
                  <th className="num">Con desc. ERP</th><th className="num">Con desc. nuestro</th>
                  <th>Igual</th>
                </tr>
              </thead>
              <tbody>
                {r.comparacion.map((c, i) => (
                  <tr key={i}>
                    <td className="mono">{c.codigoItem}</td>
                    <td className="num">{exacto(c.descuentoEnElErp)}</td>
                    <td className="num">{porcentaje(c.descuentoQueDevolvemos)}</td>
                    <td className="num">{exacto(c.netoEnElErp)}</td>
                    <td className="num">{exacto(c.netoQueDevolvemos)}</td>
                    <td className="num">{exacto(c.netoConDescuentoEnElErp)}</td>
                    <td className="num">{exacto(c.netoConDescuentoQueDevolvemos)}</td>
                    <td>
                      {c.coincide
                        ? <span className="pill ok">si</span>
                        : <span className="pill error">NO</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Tarjeta>

          <Tarjeta
            titulo="Lo que recibe la tienda"
            ayuda="Identico a lo que devuelve POST /v1/{tenant}/valorizaciones. Si la pantalla de la tienda no muestra esto, el problema es de la tienda."
          >
            <table>
              <thead>
                <tr>
                  <th>Item</th><th className="num">Cant.</th><th className="num">Neto</th>
                  <th className="num">Desc.</th><th className="num">Neto c/desc.</th>
                  <th>Por que</th>
                </tr>
              </thead>
              <tbody>
                {r.nuestraRespuesta.lineas.map((l, i) => (
                  <tr key={i}>
                    <td className="mono">
                      {l.codigo}
                      {l.creadaPorPromo && (
                        <span className="pill ok" style={{ marginLeft: 6 }}
                              title="Esta linea no la pidio el cliente: la regalo una promo">
                          regalada
                        </span>
                      )}
                    </td>
                    <td className="num">{exacto(l.cantidad)}</td>
                    <td className="num">{pesos(l.neto)}</td>
                    <td className="num">{porcentaje(l.descuento)}</td>
                    <td className="num">{pesos(l.netoConDescuento)}</td>
                    <td style={{ whiteSpace: 'normal' }}>
                      {l.bonificaciones.length === 0
                        ? <span className="tenue">sin descuento</span>
                        : l.bonificaciones.map((b, j) => (
                          <div key={j}>
                            <span className="mono">{b.id}</span> {b.nombre}
                            {' '}<span className="pill tenue">{porcentaje(b.descuento)}</span>
                            {b.condiciones.length > 0 && (
                              <div className="tenue" style={{ fontSize: 12 }}>
                                {b.condiciones.map((c) =>
                                  `${c.descripcion ?? c.tipo}: ${c.valores.join(', ')}`).join(' · ')}
                              </div>
                            )}
                          </div>
                        ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Tarjeta>

          <Tarjeta
            titulo="Lo que contesto el ERP, crudo"
            ayuda="Sin normalizar. El descuento aca viene como fraccion."
          >
            <pre className="json">{bonito(r.respuestaCrudaDelErp)}</pre>
          </Tarjeta>

          <Tarjeta
            titulo="Lo que le mandamos al ERP"
            ayuda="Con los nombres de campo de GESCOM (CodigoItem, no codigo). Se pega en Postman tal cual."
          >
            <pre className="json">{bonito(r.pedidoEnviadoAlErp)}</pre>
          </Tarjeta>
        </>
      )}
    </>
  );
}

/** El crudo se muestra indentado si es JSON valido, y tal cual si no lo es. */
function bonito(texto: string | null): string {
  if (!texto) return '(vacio)';
  try {
    return JSON.stringify(JSON.parse(texto), null, 2);
  } catch {
    return texto;
  }
}
