/**
 * Cliente de api-bonificaciones para AX-Tienda.
 *
 * Escrito para pegarse en `js/` de pwa-tienda: módulo ES, `fetch`, y el mismo patrón `IS_PROD`
 * que ya usa `js/api.js` (same-origin en producción, host absoluto fuera de producción).
 *
 * Qué hace este servicio: dice **qué descuento le corresponde a un pedido y por qué**. El número
 * lo calcula el ERP de la distribuidora (GESCOM), no el gateway ni la tienda.
 *
 * Qué NO hace: no pone el precio (lo pone la tienda), no calcula impuestos (eso es MotorFiscal) y
 * no crea ni confirma pedidos. Es solo lectura: se puede llamar las veces que haga falta.
 *
 * En el checkout va ANTES de MotorFiscal:
 *   carrito -> bonificaciones -> neto con descuento -> MotorFiscal -> IVA y percepciones
 */

// Mismo criterio que js/api.js. En producción la API cuelga del MISMO host que la tienda, como
// MotorFiscal (que está en /api/impuestos), así que la llamada es same-origin y no hay CORS.
const IS_PROD =
  location.hostname === '18.235.145.108' ||
  location.hostname === 'tienda.axumweb.com';

// ⚠️ CONFIRMAR ESTA RUTA antes de usar en producción: el servicio todavía no está deployado.
// La ruta planificada es /api/bonificaciones, al lado de /api/impuestos.
const BASE_PROD = '/api/bonificaciones';

// Para desarrollo: el gateway corriendo en la máquina de quien lo levanta.
const BASE_DEV = 'http://localhost:8081';

const BASE = IS_PROD ? BASE_PROD : BASE_DEV;

/**
 * La clave de la distribuidora. **Hay que pedirla**: se genera desde el panel del gateway y se
 * muestra una sola vez.
 *
 * ⚠️ Si la tienda llama desde el navegador, esta clave queda a la vista en el DevTools. Está
 * asumido: la clave del checkout **solo puede valorizar**, no puede leer el catálogo de
 * bonificaciones de la distribuidora. Aun así, no la pongas en el repo: tiene que venir de la
 * configuración de la tienda, igual que cualquier otra credencial.
 */
let apiKey = '';

export function configurarBonificaciones({ clave }) {
  apiKey = clave;
}

/**
 * Pide el descuento de un carrito.
 *
 * @param {string} tenant   código de la distribuidora (el mismo que usa el resto de la suite)
 * @param {string} cliente  código de cliente DEL ERP. Si no existe en el ERP, tira
 *                          ErrorDeBonificaciones con codigo 'CLIENTE_INEXISTENTE'
 * @param {Array<{codigo: string, cantidad: number, precioUnitario?: number}>} items
 *        El carrito ENTERO, en una sola llamada. `precioUnitario` es POR UNIDAD, no el total de
 *        la línea.
 * @param {{listaPrecio?: string, referencia?: string}} [opciones]
 *        `listaPrecio` solo hace falta para los ítems que NO traen precio.
 *        `referencia` es un identificador tuyo (el carrito); se devuelve tal cual.
 */
export async function valorizar(tenant, cliente, items, opciones = {}) {
  if (!apiKey) throw new Error('Falta configurarBonificaciones({ clave }).');
  if (!items || items.length === 0) throw new Error('El carrito está vacío.');

  const url = `${BASE}/v1/${encodeURIComponent(tenant)}/valorizaciones`;

  let resp;
  try {
    resp = await fetch(url, {
      method: 'POST',
      headers: { 'x-api-key': apiKey, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        cliente,
        listaPrecio: opciones.listaPrecio,
        referencia: opciones.referencia,
        items,
      }),
    });
  } catch {
    // fetch solo tira por red o CORS. En un checkout esto NO debería voltear la compra: ver
    // `conDescuentos` más abajo.
    throw new ErrorDeBonificaciones('RED', `No se pudo contactar a ${url}.`);
  }

  const texto = await resp.text();
  let datos = null;
  try {
    datos = texto ? JSON.parse(texto) : null;
  } catch { /* el cuerpo no es JSON: cae en el throw de abajo */ }

  if (!resp.ok) {
    const e = datos || {};
    throw new ErrorDeBonificaciones(e.codigo || `HTTP_${resp.status}`, e.mensaje || texto);
  }

  // Si `supuestos` no viene vacío, es algo que el gateway resolvió porque el pedido no lo traía.
  // Vale mirarlo en desarrollo: suele ser un dato que la tienda debería estar mandando.
  if (!IS_PROD && datos.supuestos?.length) {
    console.warn('[bonificaciones] supuestos:', datos.supuestos.map((s) => s.mensaje));
  }
  return datos;
}

/** Error con el código de dominio del gateway, para ramificar sin leer el texto. */
export class ErrorDeBonificaciones extends Error {
  constructor(codigo, mensaje) {
    super(mensaje);
    this.name = 'ErrorDeBonificaciones';
    this.codigo = codigo;
  }
}

/**
 * Lo que probablemente quieras usar: aplica los descuentos al carrito y **nunca voltea la compra**.
 *
 * Si el gateway o el ERP no responden, devuelve el carrito sin descuento y con `huboError`. Un
 * cliente que no puede comprar porque no pudimos calcularle un descuento es peor que un cliente
 * que compra sin el descuento — y el número definitivo lo fija el ERP cuando el pedido se
 * confirma, así que el descuento se recupera ahí.
 *
 * **Decidilo vos**: si para el negocio es inaceptable vender sin el descuento, cambiá esto por un
 * error visible. Lo que no conviene es quedarse a mitad de camino.
 */
export async function conDescuentos(tenant, cliente, items, opciones = {}) {
  try {
    const r = await valorizar(tenant, cliente, items, opciones);
    return { lineas: r.lineas, totales: r.totales, supuestos: r.supuestos, huboError: null };
  } catch (e) {
    const codigo = e instanceof ErrorDeBonificaciones ? e.codigo : 'DESCONOCIDO';
    return {
      // Sin descuento, pero con el carrito intacto para poder seguir comprando.
      lineas: items.map((i) => ({
        codigo: i.codigo,
        cantidad: i.cantidad,
        descuento: 0,
        creadaPorPromo: false,
        bonificaciones: [],
      })),
      totales: null,
      supuestos: [],
      huboError: { codigo, mensaje: e.message },
    };
  }
}

/**
 * El texto para mostrarle al cliente por qué ganó el descuento.
 *
 * Esta es la "traza": `bonificaciones[]` dice qué bonificación lo otorgó y qué condiciones la
 * dispararon. La `descripcion` de cada condición la escribe el propio ERP, así que explica mejor
 * que cualquier texto que armemos nosotros.
 */
export function porQueTieneDescuento(linea) {
  return (linea.bonificaciones || []).map((b) => ({
    nombre: b.nombre,
    porcentaje: b.descuento,
    porque: (b.condiciones || [])
      .map((c) => c.descripcion || c.tipo)
      .join(' y '),
  }));
}

/**
 * ⚠️ EL ERROR MÁS FÁCIL DE COMETER.
 *
 * El número a aplicar es `linea.descuento` (el total de la línea, que calculó el ERP).
 * **No sumes `linea.bonificaciones[].descuento`**: eso es el detalle de quién otorgó qué, una
 * línea puede tener más de una, y no está verificado que compongan sumando.
 *
 * El descuento viene en PORCENTAJE: `10` es 10%. (Ojo: Axum usa la convención opuesta en
 * percepciones, así que es fácil equivocarse viniendo de ahí.)
 */
export function aplicarDescuento(neto, linea) {
  return neto - (neto * Number(linea.descuento)) / 100;
}

/**
 * El ahorro para el cartelito de "te ahorraste $X" — con una parte que SOLO vos podés calcular.
 *
 * Verificado: cuando una promo regala unidades, la línea regalada trae el precio del ERP en su
 * `neto`, no el tuyo, aunque hayas mandado `precioUnitario` (vos no le pusiste precio a algo que
 * no pediste). Entonces el ahorro se parte en dos:
 *
 *  - `porDescuento`: la plata que descontaron las bonificaciones sobre las líneas que pediste.
 *    Es `neto - netoConDescuento` de las líneas con `creadaPorPromo: false`, y es exacto porque
 *    esas líneas sí usan tu precio.
 *  - `regalos`: los artículos que te regalaron, con su cantidad. **El gateway NO sabe cuánto
 *    valen a tu precio** (nunca vio tu precio para una unidad que no pediste), así que esto NO
 *    trae un importe: ponele vos tu precio de lista × cantidad si querés sumarlo al cartel.
 *
 * Para COBRAR no uses nada de esto: usá `totales.netoConDescuento`, que siempre es correcto.
 */
export function ahorro(respuesta) {
  const lineas = respuesta.lineas || [];
  const porDescuento = lineas
    .filter((l) => !l.creadaPorPromo)
    .reduce((acc, l) => acc + (Number(l.neto) - Number(l.netoConDescuento)), 0);

  const regalos = lineas
    .filter((l) => l.creadaPorPromo)
    .map((l) => ({ codigo: l.codigo, cantidad: l.cantidad }));

  // Sin regalos, `porDescuento` ya es el ahorro completo y se puede mostrar tal cual.
  return { porDescuento, regalos };
}
