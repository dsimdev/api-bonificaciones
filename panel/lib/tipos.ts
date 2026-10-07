/** Lo que devuelve la API. Los nombres son los del JSON, sin traducir. */

export interface Distribuidora {
  codigo: string;
  nombre: string | null;
  host: string;
  realm: string;
  /** true cuando la fila pisa host o realm en vez de usar la convencion. Explica rarezas. */
  fueraDeConvencion: boolean;
  /** el usuario de GESCOM. La clave NUNCA viaja. */
  usuario: string;
  activa: boolean;
  creadaEn: string;
  creadaPor: string | null;
  actualizadaEn: string;
  actualizadaPor: string | null;
}

/** @param criterios cuantos trajo del ERP: es la prueba de que la credencial anda. */
export interface Resultado {
  codigo: string;
  criterios: number;
  mensaje: string;
}

/** @param claveDeLaTienda en claro, y es la UNICA vez que se ve. */
export interface Creada extends Resultado {
  claveDeLaTienda: string;
}

export interface Sesion {
  token: string;
  usuario: string;
  duracionMinutos: number;
}

export interface Usuario {
  id: number;
  usuario: string;
  nombre: string | null;
  activo: boolean;
}

// --- Valorizacion. El descuento viene en PORCENTAJE (10 = 10%).

export interface Condicion {
  tipo: string;
  descripcion: string | null;
  valores: string[];
  invertida: boolean;
  cantidadMinima: number | null;
}

export interface Bonificacion {
  id: string | null;
  nombre: string | null;
  descuento: number;
  condiciones: Condicion[];
}

export interface Linea {
  codigo: string;
  cantidad: number;
  neto: number;
  descuento: number;
  netoConDescuento: number;
  /** true = la linea no la pidio el cliente, la regalo una promo. */
  creadaPorPromo: boolean;
  bonificaciones: Bonificacion[];
}

export interface Valorizacion {
  fuente: string;
  tenant: string;
  calculadoPor: 'ERP' | 'GATEWAY';
  consultadoEn: string;
  referencia: string | null;
  supuestos: { codigo: string; mensaje: string }[];
  totales: { neto: number; descuento: number; netoConDescuento: number };
  lineas: Linea[];
}

/**
 * El numero del ERP al lado del que exponemos, para un item.
 *
 * `coincide: false` es el unico caso donde el culpable somos nosotros sin lugar a dudas.
 */
export interface Comparacion {
  codigoItem: string;
  /** FRACCION, como la da GESCOM (0.1) */
  descuentoEnElErp: number;
  /** PORCENTAJE, como lo expone el contrato (10) */
  descuentoQueDevolvemos: number;
  netoEnElErp: number;
  netoQueDevolvemos: number;
  netoConDescuentoEnElErp: number;
  netoConDescuentoQueDevolvemos: number;
  coincide: boolean;
}

/** Las tres capas, para decidir de quien es el problema. */
export interface Diagnostico {
  /** JSON con los nombres de campo de GESCOM: se pega en Postman tal cual */
  pedidoEnviadoAlErp: string | null;
  respuestaCrudaDelErp: string | null;
  nuestraRespuesta: Valorizacion;
  comparacion: Comparacion[];
  lineasQueNoCierran: string[];
  veredicto: string;
}

export interface ItemAProbar {
  codigo: string;
  cantidad: string;
}
