/**
 * Cliente de api-bonificaciones para la tienda. Va en la carpeta `js/` de la tienda.
 *
 * Qué hace el servicio: dice qué descuento le corresponde a un carrito y por qué. El número lo
 * calcula el ERP de la distribuidora. Referencia completa: contrato.md, en esta misma carpeta.
 */

// En el mismo dominio que la tienda, como MotorFiscal en /api/impuestos.
const BASE = '/api/bonificaciones';

// Una valorización tarda ~2 s. Pasado este tiempo se corta, para no dejar el checkout colgado.
const TIEMPO_MAXIMO_MS = 10000;

/** Una clave por distribuidora: la de una no sirve para otra. */
const claves = new Map();

/** Si está definida, se usa en vez de llamar al servicio. Ver usarRespuestasDePrueba. */
let respuestaDePrueba = null;

/**
 * Registra la clave de una distribuidora. Una vez por cada distribuidora que use la tienda.
 * La clave sale de la configuración de la tienda, no del código.
 */
export function configurarBonificaciones({ tenant, clave }) {
  claves.set(tenant, clave);
}

/**
 * Para desarrollar sin el servicio (en local la ruta /api/bonificaciones no existe): a partir de
 * acá, `valorizar` devuelve lo que devuelva `fn(tenant, cliente, items)`, sin llamar a nada. Sirve
 * con los JSON de ejemplos/. Si `fn` tira un ErrorDeBonificaciones, se comporta como ese error.
 * `usarRespuestasDePrueba(null)` vuelve al servicio real.
 */
export function usarRespuestasDePrueba(fn) {
  respuestaDePrueba = fn;
}

/**
 * Error con un código para decidir sin leer el texto. Los códigos son los del servicio (ver
 * contrato.md), más tres que genera este módulo:
 *   SIN_CONFIGURAR      no se llamó a configurarBonificaciones para ese tenant
 *   SIN_RESPUESTA       no hubo respuesta: sin conexión, o pasaron 10 segundos
 *   RESPUESTA_INVALIDA  la respuesta no es la del servicio (p. ej. una página de error de IIS)
 */
export class ErrorDeBonificaciones extends Error {
  constructor(codigo, mensaje, status = null) {
    super(mensaje);
    this.name = 'ErrorDeBonificaciones';
    this.codigo = codigo;
    this.status = status;
  }
}

/**
 * Pide los descuentos de un carrito. Si algo falla, tira ErrorDeBonificaciones.
 * Para el checkout conviene `conDescuentos`, más abajo.
 *
 * @param {string} tenant   código de la distribuidora
 * @param {string} cliente  código del cliente en el ERP de la distribuidora
 * @param {Array<{codigo: string, cantidad: number, precioUnitario?: number}>} items
 *        El carrito ENTERO, en una llamada, con cada artículo una sola vez.
 *        `precioUnitario` es por unidad y sin impuestos.
 * @param {{listaPrecio?: string, referencia?: string}} [opciones]
 *        `listaPrecio`: solo para ítems SIN precio. `referencia`: tuya, vuelve tal cual.
 */
export async function valorizar(tenant, cliente, items, opciones = {}) {
  if (respuestaDePrueba) return respuestaDePrueba(tenant, cliente, items, opciones);

  const clave = claves.get(tenant);
  if (!clave) {
    throw new ErrorDeBonificaciones('SIN_CONFIGURAR', `Falta configurarBonificaciones para "${tenant}".`);
  }

  const control = new AbortController();
  const corte = setTimeout(() => control.abort(), TIEMPO_MAXIMO_MS);
  let resp;
  let texto;
  try {
    resp = await fetch(`${BASE}/v1/${encodeURIComponent(tenant)}/valorizaciones`, {
      method: 'POST',
      headers: { 'x-api-key': clave, 'Content-Type': 'application/json' },
      body: JSON.stringify({ cliente, listaPrecio: opciones.listaPrecio, referencia: opciones.referencia, items }),
      signal: control.signal,
    });
    texto = await resp.text();
  } catch {
    throw new ErrorDeBonificaciones('SIN_RESPUESTA', 'El servicio de bonificaciones no respondió.');
  } finally {
    clearTimeout(corte);
  }

  let datos = null;
  try {
    datos = JSON.parse(texto);
  } catch { /* no es JSON: se informa abajo */ }

  if (!resp.ok) {
    if (datos && datos.codigo) throw new ErrorDeBonificaciones(datos.codigo, datos.mensaje, resp.status);
    throw new ErrorDeBonificaciones('RESPUESTA_INVALIDA', `HTTP ${resp.status}`, resp.status);
  }
  if (!datos || !Array.isArray(datos.lineas)) {
    throw new ErrorDeBonificaciones('RESPUESTA_INVALIDA', 'La respuesta no tiene el formato esperado.', resp.status);
  }
  return datos;
}

/** Errores que no se arreglan solos: alguien tiene que corregir algo. */
const HAY_QUE_CORREGIR = new Set(['SIN_CONFIGURAR', 'NO_AUTORIZADO', 'DEMASIADOS_INTENTOS',
  'PEDIDO_INVALIDO', 'CREDENCIALES_INVALIDAS']);

/**
 * Lo que conviene usar en el checkout. **Nunca corta la venta**: si no se puede saber el
 * descuento, devuelve el carrito con 0% y con tus precios, y dice por qué en `motivoSinDescuento`:
 *
 *   { codigo, mensaje, hayQueCorregir }
 *
 * `hayQueCorregir: true` (clave inválida, sin configurar o bloqueada por intentos fallidos, pedido
 * mal armado, credenciales del servicio con el ERP) significa que no se va a arreglar solo: además se registra en la consola,
 * y conviene avisarlo. Con `false` (el servicio no respondió, el cliente no está en el ERP, etc.)
 * es una situación esperable.
 *
 * Si para el negocio no se puede vender sin descuento, usá `valorizar` y mostrá el error.
 */
export async function conDescuentos(tenant, cliente, items, opciones = {}) {
  try {
    const respuesta = await valorizar(tenant, cliente, items, opciones);
    return { ...respuesta, motivoSinDescuento: null };
  } catch (e) {
    if (!(e instanceof ErrorDeBonificaciones)) throw e;
    const hayQueCorregir = HAY_QUE_CORREGIR.has(e.codigo);
    if (hayQueCorregir) console.error(`[bonificaciones] ${e.codigo}: ${e.message}`);
    return sinDescuento(tenant, items, opciones, { codigo: e.codigo, mensaje: e.message, hayQueCorregir });
  }
}

/** Misma forma que una respuesta del servicio, con 0% y tus precios. */
function sinDescuento(tenant, items, opciones, motivo) {
  const lineas = items.map((i) => {
    const neto = i.precioUnitario != null ? i.precioUnitario * i.cantidad : null;
    return { codigo: String(i.codigo), cantidad: i.cantidad, neto, descuento: 0, netoConDescuento: neto,
      creadaPorPromo: false, bonificaciones: [] };
  });
  const todosConPrecio = lineas.every((l) => l.neto != null);
  const total = todosConPrecio ? lineas.reduce((a, l) => a + l.neto, 0) : null;
  return {
    fuente: null,
    tenant,
    calculadoPor: null, // null = no lo calculó nadie: es el respaldo sin descuento
    consultadoEn: null,
    referencia: opciones.referencia ?? null,
    supuestos: [],
    // Si algún ítem no trae precio, no hay total: ese precio lo tiene tu catálogo.
    totales: todosConPrecio ? { neto: total, descuento: 0, netoConDescuento: total } : null,
    lineas,
    motivoSinDescuento: motivo,
  };
}

/**
 * La línea de la respuesta que corresponde a un ítem del carrito, o null si no vino.
 *
 * Hay que buscarla por código: el orden de `lineas` NO es el del carrito, y una promo puede
 * agregar otra línea con el mismo código, marcada `creadaPorPromo: true` (el regalo). Los códigos
 * de la respuesta son texto: se compara como texto aunque el carrito los tenga como número.
 */
export function lineaDelItem(respuesta, codigo) {
  return respuesta.lineas.find((l) => String(l.codigo) === String(codigo) && !l.creadaPorPromo) || null;
}

/**
 * Las líneas que agregó una promo (unidades regaladas). No las pidió el cliente: se muestran como
 * regalo, no se cobran y no van a MotorFiscal.
 */
export function regalos(respuesta) {
  return respuesta.lineas.filter((l) => l.creadaPorPromo);
}

/**
 * Tu neto de la línea con el descuento aplicado. Es la forma de usar el descuento: sobre TU neto
 * (tu precio × cantidad), con `linea.descuento` (porcentaje: 10 = 10%). Con una línea que no vino
 * (`null`), devuelve el neto sin descuento.
 *
 * No sumes `linea.bonificaciones[].descuento`: es el detalle de quién otorgó qué, una línea puede
 * tener más de una y no está verificado que se sumen.
 */
export function aplicarDescuento(neto, linea) {
  if (!linea) return neto;
  return neto - (neto * Number(linea.descuento)) / 100;
}

/**
 * Cuánta plata descontaron las bonificaciones sobre lo que pidió el cliente.
 *
 * No incluye los regalos: la línea regalada trae el precio del ERP, no el tuyo, así que su
 * "ahorro" no está en tus precios. Para mostrarlos, usá `regalos(respuesta)` con tu precio.
 */
export function ahorroPorDescuento(respuesta) {
  return respuesta.lineas
    .filter((l) => !l.creadaPorPromo && l.neto != null)
    .reduce((acc, l) => acc + (Number(l.neto) - Number(l.netoConDescuento)), 0);
}

/**
 * Qué bonificación dio el descuento de una línea, y por qué condiciones. Sirve para rastrear y
 * para soporte. Los nombres los escribe la distribuidora en su ERP y suelen ser internos: antes de
 * mostrarlos al cliente, miralos con datos reales.
 */
export function porQueTieneDescuento(linea) {
  return ((linea && linea.bonificaciones) || []).map((b) => ({
    id: b.id,
    nombre: b.nombre,
    porcentaje: b.descuento,
    condiciones: (b.condiciones || []).map((c) => ({
      descripcion: c.descripcion || c.tipo,
      valores: c.valores || [],
      // `invertida`: la condición se cumple cuando NO está en `valores`.
      invertida: Boolean(c.invertida),
    })),
  }));
}
