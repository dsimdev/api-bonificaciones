'use client';

import { useState, type ReactNode } from 'react';

export function Tarjeta(
  { titulo, ayuda, id, children }:
    { titulo?: string; ayuda?: string; id?: string; children: ReactNode },
) {
  return (
    <section className="tarjeta" id={id}>
      {titulo && <h2>{titulo}</h2>}
      {ayuda && <p className="ayuda">{ayuda}</p>}
      {children}
    </section>
  );
}

export function Aviso(
  { tipo = 'error', children }: { tipo?: 'error' | 'ok' | 'warn'; children?: ReactNode },
) {
  if (!children) return null;
  return <div className={`aviso ${tipo}`}>{children}</div>;
}

export interface Accion {
  error: string;
  ok: string;
  cargando: boolean;
  correr: <T>(
    fn: () => Promise<T>, mensajeOk?: string, opciones?: { recargar?: boolean },
  ) => Promise<T | undefined>;
  setError: (v: string) => void;
  setOk: (v: string) => void;
}

/**
 * Envuelve una accion: bloquea el boton, muestra el error y refresca al terminar bien.
 *
 * `recargar` (default true) decide si se llama a `alTerminar` despues de `fn`. En false para las
 * acciones que ya dejan el estado como corresponde -- si no, el recargo posterior puede pisar el
 * resultado con datos viejos capturados en su closure.
 */
export function useAccion(alTerminar?: () => Promise<void>): Accion {
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [cargando, setCargando] = useState(false);

  const correr = async <T,>(
    fn: () => Promise<T>, mensajeOk?: string, opciones?: { recargar?: boolean },
  ): Promise<T | undefined> => {
    setError(''); setOk(''); setCargando(true);
    try {
      const r = await fn();
      setOk(mensajeOk ?? 'Listo.');
      if (alTerminar && opciones?.recargar !== false) await alTerminar();
      return r;
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return undefined;
    } finally {
      setCargando(false);
    }
  };

  return { error, ok, cargando, correr, setError, setOk };
}

/**
 * OJO: aca el descuento YA viene en porcentaje (10 = 10%), asi que esto no multiplica por nada.
 *
 * El panel de MotorFiscal tiene una funcion con el mismo nombre que SI multiplica por 100, porque
 * sus alicuotas se guardan como fraccion. Copiarla tal cual mostraria 1000% donde va 10%: es la
 * misma confusion fraccion/porcentaje que ya nos costo un bug en el conector, con la diferencia de
 * que ahi daba 0,1% y aca daria 1000%.
 */
export function porcentaje(valor: number | string | null | undefined): string {
  if (valor === null || valor === undefined || valor === '') return '—';
  const n = Number(valor);
  return `${n.toFixed(4).replace(/\.?0+$/, '')}%`;
}

/**
 * Los importes llegan SIN redondear (el ERP da hasta 6 decimales) y aca se muestran enteros con
 * 2 decimales igual que en una factura. El valor exacto esta en el crudo, que esta al lado.
 */
export function pesos(valor: number | string | null | undefined): string {
  if (valor === null || valor === undefined || valor === '') return '—';
  return Number(valor).toLocaleString('es-AR',
    { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** Igual que pesos pero sin redondear, para cuando lo que importa es el numero exacto. */
export function exacto(valor: number | string | null | undefined): string {
  if (valor === null || valor === undefined || valor === '') return '—';
  return String(valor);
}

export function cuando(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('es-AR');
}
