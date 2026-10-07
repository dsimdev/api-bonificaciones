# Multi-tenant y autenticación

> Escrito el 2026-10-07, cuando aparecieron dos datos que cambian el diseño: **esto escala a ~1000
> tiendas**, y **la tienda nos llama desde el navegador** en el checkout, igual que llama a
> MotorFiscal.
>
> Casi todo acá sale de leer `api-impuestos`, que ya resolvió el mismo problema con el mismo
> consumidor en el mismo servidor. **No inventar: copiar.**

## Lo que muere con 1000 tiendas

La configuración por **variable de entorno** que hay hoy (`ConfiguracionDeDistribuidoras`). Con dos
distribuidoras anda; con mil no:

- 1000 × (host, realm, usuario, clave, api-key) no entra en un `.env`;
- cada alta sería un reinicio;
- las claves de GESCOM quedarían en texto plano en el entorno del proceso.

Era un andamio razonable para la Fase 1, **no es el diseño**.

## Cómo lo resolvió MotorFiscal

| Pieza | Qué hace |
|---|---|
| `RepositorioDeCredenciales` | las api-keys viven en **SQL Server**, no en config. El servidor ya lo tiene |
| `InterceptorDeApiKey` | valida el header `x-api-key` y **que la credencial sea del tenant de la ruta** |
| `Credencial.Alcance` | `CALCULO` (la que va en la tienda) vs `ADMIN` (back-office, nunca sale de ahí) |
| `ClaveMaestra` | una sola clave que vale para todos los tenants, para operar el panel |
| `LimitadorDeIntentosDeApiKey` | bloquea al tenant tras N intentos fallidos (fuerza bruta) |
| `LimitadorDeCalculos` | cuota **por credencial**, contando todos los pedidos: un loop sin backoff de una tienda no se come la capacidad del resto |
| `CifradoDeSecretos` | secretos cifrados en reposo |

Lo importante del interceptor, citando su propio javadoc: *"que sea del mismo tenant que la URL —si
no, una distribuidora podría calcular con las reglas de otra"*. Es el mismo agujero que el test de
aislamiento de tokens cubre del lado de GESCOM, pero del lado de la entrada.

## La key en el navegador: MotorFiscal YA asume que se filtra

El javadoc de `Credencial` lo dice literal:

> *`CALCULO` es la que va en la tienda: solo puede calcular. `ADMIN` además cambia reglas e
> ingiere, y nunca sale del back-office. **Separarlas es lo que evita que una key filtrada en un
> frontend pueda cambiar lo que se le factura a la gente.***

O sea: el diseño **da por hecho que la key del frontend es pública** y limita el daño por alcance.
Es la postura correcta y hay que adoptarla.

## ⚠️ Pero acá el daño filtrado es peor que en MotorFiscal

Y esto no se puede copiar sin pensarlo. Una key de `CALCULO` de MotorFiscal filtrada deja calcular
impuestos — y **las alícuotas son públicas**, son normativa. Una key nuestra filtrada deja:

1. **`GET /criterios`** → **toda la estructura de descuentos de la distribuidora**. Qué bonifica, a
   quién, cuánto. Información comercial, no normativa: un competidor paga por eso.
2. **`POST /valorizaciones`** enumerando códigos de cliente → **qué precio paga cada cliente**.

Por eso, dos reglas propias que MotorFiscal no necesita:

- **`/criterios` NO va con la key del navegador.** Alcance separado: la del checkout solo valoriza.
  El catálogo es back-office.
- **Valorizar sigue permitiendo enumerar clientes.** Eso no lo cierra ninguna api-key, porque el
  navegador la tiene. Ver abajo.

## El cliente: lo único que la key no puede probar

En un checkout el navegador **es** un cliente de la distribuidora y tiene derecho a ver **su**
precio. El riesgo es que vea el de **otro**. Tres niveles:

| Nivel | Qué hace falta | Qué tan cerrado queda |
|---|---|---|
| **A. Key por tenant + alcance** | solo nosotros | una key filtrada lee los precios de cualquier cliente **de esa distribuidora** |
| **B. A + rate limit por credencial** | solo nosotros | la enumeración masiva se vuelve lenta y visible. **Es lo que hace MotorFiscal** |
| **C. Token corto firmado por el backend de la tienda** que diga "este navegador es el cliente 8380" | la tienda tiene que emitirlo | cerrado de verdad: no se puede pedir el precio de otro |

**Recomendación: arrancar en B y proponer C.** B se hace de nuestro lado y nos pone a la par de
MotorFiscal. C es lo correcto y hay que pedírselo a la tienda — pero no bloquea: el contrato puede
aceptar el token desde el día uno y validarlo cuando exista.

## CORS: probablemente no haya problema

**MotorFiscal no tiene configuración de CORS en Spring.** La pista de por qué: se compila con
`-PpanelBasePath=/api/impuestos/admin`, o sea que está montado **bajo el mismo dominio** detrás de
IIS. Si la tienda y el gateway comparten origen, **CORS no existe** — es el mismo truco que usaron
para el panel ("el panel queda en el MISMO origen que la API").

**A confirmar**: si la tienda llama a MotorFiscal sin CORS, montamos igual y listo. Si resulta que
sí hay CORS en algún lado (IIS), copiamos esa configuración.

## Lo que esto reabre

- **¿Hace falta base de datos?** → **Sí, y ya no por auditoría: por configuración.** 1000 tenants
  con sus credenciales y sus api-keys necesitan SQL Server + Flyway, igual que `api-impuestos`.
- **¿Panel?** → Ahora sí, probablemente. Con 1000 tiendas el alta no la hace una persona editando
  un archivo. Pero antes de construirlo hay que preguntar: **¿el alta puede ser automática?** Si
  una distribuidora ya existe en Axum, lo lógico es provisionarla acá desde ahí, no a mano.
  Un panel para 1000 altas manuales es trabajo que capaz no hay que hacer.
