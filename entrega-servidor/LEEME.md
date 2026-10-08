# Entrega para el primer deploy — api-bonificaciones

**Esta carpeta instala el gateway en el servidor.** Es autocontenida: el jar ya está compilado y
los scripts están todos acá. Se pasa completa.

No confundir con `entrega-tienda/`: esa es para quien escribe el **checkout** y consume la API por
HTTP. Esta es para quien **instala el servicio**. Son dos tareas distintas, y el jar va solo en
esta — el que integra la tienda nunca lo toca.

---

## Qué hay acá

```
LEEME.md                       ← esto
bonif-app-0.6.1-prod.jar       ← el jar de producción, YA compilado (ver abajo)
scripts/
  crear-base.sql               ← crea la base y el login de SQL
  deploy.ps1                   ← build + subida + restart por FTP/WinRM (para los RE-deploys)
  servidor/
    GUIA-PRIMER-DEPLOY.md      ← el procedimiento, paso a paso. EMPEZÁ ACÁ.
    preparar-sistema.ps1
    preparar-iis.ps1
    simular-proxy-anidado.ps1
    bonificaciones.xml         ← WinSW (servicio de Windows)
    web.config                 ← reverse proxy de IIS
```

Los `scripts/` son copia de los del repo `api-bonificaciones`. Si algún día divergen, la fuente de
verdad es el repo.

---

## El procedimiento está en la guía

Seguí **`scripts/servidor/GUIA-PRIMER-DEPLOY.md`** de arriba a abajo. Lo que sigue acá son solo las
dos diferencias por venir el jar ya hecho.

## El jar ya está compilado — saltate el paso 2 de la guía

El paso 2 de la guía te dice que compiles el jar vos. **No hace falta: `bonif-app-0.6.1-prod.jar` ya
está en esta carpeta**, horneado para la ruta `/api/bonificaciones` (verificado abriendo el jar, y
probado a través del simulador de proxy anidado: `/health`, el panel, el login y Swagger). Copialo
tal cual como dice el paso 3. El `/health` del servidor tiene que decir `"version": "0.6.1"`.

**La única condición es que IIS lo cuelgue en `/api/bonificaciones`** (paso 4 de la guía). El
`basePath` del panel está horneado en el jar: si lo montás en otra ruta, el panel carga en blanco con
404 y hay que **recompilar** — y para eso necesitás el repo `api-bonificaciones`, no alcanza esta
carpeta. Pedilo si la ruta va a ser otra. Si es la planificada, no toques nada.

## Los secretos se generan en el servidor, no viajan en esta carpeta

`preparar-sistema.ps1` (paso 1) genera en el server la `CIFRADO_KEY`, la clave de la base y la del
primer usuario del panel. **Nada de eso está acá a propósito**: un `.xml` o `.env` con secretos
adentro se sube por FTP y todo lo que tenga viaja. Guardá lo que imprime ese paso; la `CIFRADO_KEY`
es la que no se puede perder, porque cifra las claves de GESCOM de todas las distribuidoras.

Tiene que quedar como variable de entorno **de máquina** (así la deja el script). Si el servicio
arranca sin ella, levanta igual, pero toda valorización falla con un 500 al querer descifrar la
clave de GESCOM. Si ves eso, es lo primero que hay que revisar.

---

## Cuando termines, dos datos para el lado de la tienda

Quien integra el checkout (el de `entrega-tienda/`) va a necesitar estas dos cosas, y recién salen
**después** de este deploy:

1. **La api-key de la distribuidora** — se genera en el panel (paso 6 de la guía) y se muestra una
   sola vez.
2. **La URL de producción confirmada** — la que haya quedado en IIS, para que deje de ser un plan.
