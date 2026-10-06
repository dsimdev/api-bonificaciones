# Entorno local

## Requisitos

- **JDK 21** (verificado: Temurin/Oracle 21.0.11). No hace falta instalar Gradle: está el wrapper.
- Nada más. Sin Docker, sin base de datos, sin Node.

## Levantar

```powershell
.\arrancar.ps1 -Build     # compila y levanta
.\arrancar.ps1            # usa el jar ya compilado
```

- salud: <http://localhost:8080/health>
- swagger: <http://localhost:8080/swagger-ui.html>
- spec OpenAPI: <http://localhost:8080/v3/api-docs>

`/health` devuelve la versión que está corriendo de verdad (la embebe el build) y **qué
distribuidoras quedaron configuradas**. Si una distribuidora no aparece ahí, le falta
configuración — se ve en el arranque y no recién en la primera consulta.

## Credenciales

```powershell
Copy-Item .env.example .env
# completar con los valores de la coleccion Postman de cada distribuidora
```

`arrancar.ps1` carga el `.env` solo. `.env` está en `.gitignore`: **no se commitea nunca**.

Sin credenciales la app igual levanta y `/health` responde; lo que falla es consultar el ERP, y
falla con un error explícito.

## Build y tests

```powershell
.\gradlew.bat build                      # compila todo y corre los tests
.\gradlew.bat test --tests '*Vigencia*'  # un test puntual
.\gradlew.bat build -PincludeErpTests    # suma los tests que pegan contra el ERP real
```

Los tests etiquetados `erp` están **excluidos por defecto**: necesitan credenciales de una
distribuidora real y red. Así `gradlew build` sigue verde en cualquier máquina y sin secretos.

## Pegarle a GESCOM a mano

El token de Keycloak dura **5 minutos**, así que hay que mintearlo y usarlo en el mismo script —
un token pegado a mano un rato después ya está vencido.

```bash
HOST=https://dyssa.gescom.online
REALM=gcw-dyssa

TOKEN=$(curl -s -X POST "https://auth.gescom.online/realms/$REALM/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "client_id=gcw-web-api&grant_type=password&username=$USUARIO&password=$CLAVE" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

curl -s "$HOST/data/cmd/ventas/api/v1/get-promociones" -H "Authorization: Bearer $TOKEN" \
  > fixtures/get-promociones-dyssa.json
```

Recordar: `get-articulos` vive en el servicio **`inventario`**, no en `ventas`. Una ruta que no
existe responde `Unity.Exceptions.InvalidRegistrationException` — es la forma de distinguir
"comando inexistente" de "comando que falló".

Detalle completo y campos verificados: `C:\Dev\docs\gescom\eval-pedido.md`.

## Git

- Se desarrolla en rama `feat/…` o `fix/…`. **Hay un hook que bloquea editar y commitear en
  `main`** (`.claude/settings.json`). Es a propósito: `main` es producción.
- Los hooks recién pegados **no se cargan hasta reiniciar la sesión de Claude Code**. Es el
  gotcha que más tiempo cuesta.
