/**
 * Export estatico: `next build` deja HTML+JS puro en out/, sin runtime de Node.
 *
 * Es lo que permite que Gradle lo empaquete dentro del jar y que Spring lo sirva en /admin, en el
 * mismo origen que la API: sin CORS, sin mixed content, y un solo artefacto para deployar. Con
 * server components o rutas de API haria falta un Node corriendo al lado, que en un servidor
 * on-premise con IIS adelante es exactamente lo que no queremos.
 *
 * Spring SIEMPRE sirve el panel compilado en /admin (fijo, ver copiarPanel en
 * bonif-app/build.gradle.kts) -- pero basePath es lo que el HTML le dice al NAVEGADOR donde pedir
 * sus chunks, y eso depende de por donde entra el navegador, no de donde vive el archivo:
 *
 *   - Acceso directo a Spring (local, o produccion sin proxy anidado): el navegador entra por
 *     /admin y basePath tiene que ser '/admin'. Es el default de abajo.
 *   - Detras de un IIS que cuelga esto como aplicacion anidada bajo una ruta que ya existe (ej.
 *     tudominio.com/api/bonificaciones): el navegador entra por /api/bonificaciones/admin. IIS le
 *     saca el prefijo antes de reenviar, asi que Spring sigue viendo /admin/... como siempre, pero
 *     el HTML tiene que pedir los chunks en la ruta COMPLETA que ve el navegador
 *     (/api/bonificaciones/admin/_next/...) o los pide en la raiz del dominio y el panel queda en
 *     BLANCO con 404 en la consola.
 *
 * En api-impuestos ese bug llego a produccion TRES veces (el redirect de /admin, swagger-ui.url y
 * swagger-ui.config-url). Por eso basePath sale de una variable de entorno al compilar
 * (PANEL_BASE_PATH) y no queda fijo: el jar para un deploy con proxy anidado se compila distinto
 * al que se prueba local. Ver bonif-app/build.gradle.kts (buildPanel).
 *
 * Lo mismo vale para los pedidos a la API (`panel/lib/api.ts`): estan escritos como paths
 * absolutos (`/admin/v1/...`), asi que basePath NO los toca -- eso es el routing de paginas de
 * Next, no fetch() a mano. Sin NEXT_PUBLIC_API_URL, detras del proxy anidado el panel pediria
 * `/admin/v1/login` contra la RAIZ del dominio. Sale de PANEL_BASE_PATH sacandole el '/admin'
 * final (son la misma ruta anidada) y es '' cuando no hay proxy.
 */
const panelBasePath = process.env.PANEL_BASE_PATH || '/admin';
const apiBaseUrl = panelBasePath.replace(/\/admin$/, '');

const nextConfig = {
  output: 'export',
  basePath: panelBasePath,
  env: {
    NEXT_PUBLIC_API_URL: apiBaseUrl,
  },
  images: { unoptimized: true },
  trailingSlash: true,
};

export default nextConfig;
