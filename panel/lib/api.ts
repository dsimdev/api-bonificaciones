'use client';

import type {
  Actividad, Creada, Criterios, Diagnostico, Distribuidora, Resultado, Sesion, Usuario,
} from './tipos';

/**
 * Cliente de la API de administracion. Dos cosas definen como funciona:
 *
 * 1. La URL es siempre **mismo origen** (Spring sirve el panel desde /admin): no hay campo de URL
 *    ni CORS que resolver. Ver next.config.mjs para el caso del proxy anidado.
 * 2. El login es por **usuario y contrasenia**, no una clave compartida: hace falta saber quien
 *    dio de alta que. Devuelve un token opaco que queda en `sessionStorage` (nunca localStorage,
 *    que sobrevive al cierre del navegador) y viaja como `Authorization: Bearer`.
 *
 * El panel NUNCA necesita la api-key de una tienda: la prueba de valorizacion va por /admin con
 * la sesion. Asi no hay que guardar la clave de la tienda en ningun lado para poder diagnosticar.
 */
const TOKEN = 'bonificaciones.token';
const USUARIO = 'bonificaciones.usuario';

export const sesion = {
  get token(): string {
    return typeof window === 'undefined' ? '' : sessionStorage.getItem(TOKEN) ?? '';
  },
  get usuario(): string {
    return typeof window === 'undefined' ? '' : sessionStorage.getItem(USUARIO) ?? '';
  },
  get baseUrl(): string {
    return process.env.NEXT_PUBLIC_API_URL ?? '';
  },
  get dentro(): boolean {
    return Boolean(sesion.token);
  },
  guardar(s: Sesion): void {
    sessionStorage.setItem(TOKEN, s.token);
    sessionStorage.setItem(USUARIO, s.usuario);
  },
  limpiar(): void {
    [TOKEN, USUARIO].forEach((k) => sessionStorage.removeItem(k));
  },
};

interface ErrorDeApi {
  codigo?: string;
  mensaje?: string;
  crudo?: string;
}

async function pedir<T>(metodo: string, ruta: string, cuerpo?: unknown): Promise<T> {
  const url = `${sesion.baseUrl}${ruta}`;
  const headers: Record<string, string> = {};
  if (sesion.token) headers.Authorization = `Bearer ${sesion.token}`;
  if (cuerpo !== undefined) headers['Content-Type'] = 'application/json';

  let resp: Response;
  try {
    resp = await fetch(url, {
      method: metodo,
      headers,
      body: cuerpo === undefined ? undefined : JSON.stringify(cuerpo),
    });
  } catch {
    // fetch solo tira por red o CORS: conviene decirlo en vez de mostrar "Failed to fetch".
    throw new Error(
      `No se pudo contactar a ${url || 'la API'}. Verifica que el servicio este levantado.`,
    );
  }

  const texto = await resp.text();
  let datos: unknown = null;
  try {
    datos = texto ? JSON.parse(texto) : null;
  } catch {
    datos = null;
  }

  if (!resp.ok) {
    const e = (datos ?? {}) as ErrorDeApi;
    // El mensaje del servidor ya esta escrito para que lo lea quien da de alta: en castellano y
    // sin codigos HTTP. Se muestra tal cual -- traducirlo aca seria tener dos versiones del
    // mismo error, y la de aca se quedaria vieja.
    throw new Error(e.mensaje || texto || `Error ${resp.status}`);
  }
  return datos as T;
}

export interface Alta {
  codigo: string;
  nombre?: string;
  usuario: string;
  clave: string;
  host?: string;
  realm?: string;
}

export interface PedidoAProbar {
  cliente: string;
  listaPrecio?: string;
  referencia?: string;
  items: { codigo: string; cantidad: number }[];
}

export const api = {
  login: (usuario: string, clave: string) =>
    pedir<Sesion>('POST', '/admin/v1/login', { usuario, clave }),
  salir: () => pedir<void>('POST', '/admin/v1/logout', { token: sesion.token }),

  distribuidoras: () => pedir<Distribuidora[]>('GET', '/admin/v1/distribuidoras'),
  // Prueba las credenciales contra GESCOM ANTES de guardar: si fallan, no se guarda nada.
  crear: (a: Alta) => pedir<Creada>('POST', '/admin/v1/distribuidoras', a),
  verificar: (codigo: string) =>
    pedir<Resultado>('POST', `/admin/v1/distribuidoras/${encodeURIComponent(codigo)}/verificar`),
  cambiarCredenciales: (codigo: string, usuario: string, clave: string) =>
    pedir<Resultado>('PUT',
      `/admin/v1/distribuidoras/${encodeURIComponent(codigo)}/credenciales`, { usuario, clave }),
  regenerarClaveDeTienda: (codigo: string) =>
    pedir<Creada>('POST',
      `/admin/v1/distribuidoras/${encodeURIComponent(codigo)}/clave-tienda`),

  // Las tres capas: lo que pedimos, el crudo del ERP y lo que devolvemos.
  diagnosticar: (codigo: string, pedido: PedidoAProbar) =>
    pedir<Diagnostico>('POST',
      `/admin/v1/distribuidoras/${encodeURIComponent(codigo)}/diagnostico/valorizacion`, pedido),

  // El catalogo por /admin y no por /v1/{tenant}/criterios: asi el panel no tiene que guardar
  // una api-key de alcance ADMIN de cada distribuidora en el navegador.
  criterios: (codigo: string, incluirNoVigentes: boolean) =>
    pedir<Criterios>('GET',
      `/admin/v1/distribuidoras/${encodeURIComponent(codigo)}/criterios`
      + `?incluirNoVigentes=${incluirNoVigentes}`),

  actividad: () => pedir<Actividad[]>('GET', '/admin/v1/metricas'),

  usuarios: () => pedir<Usuario[]>('GET', '/admin/v1/usuarios'),
  crearUsuario: (usuario: string, clave: string, nombre: string) =>
    pedir<Usuario>('POST', '/admin/v1/usuarios', { usuario, clave, nombre }),
  cambiarClave: (usuario: string, claveActual: string, clave: string) =>
    pedir<void>('POST', `/admin/v1/usuarios/${encodeURIComponent(usuario)}/clave`, { claveActual, clave }),
  desactivarUsuario: (usuario: string) =>
    pedir<void>('DELETE', `/admin/v1/usuarios/${encodeURIComponent(usuario)}`),
};
