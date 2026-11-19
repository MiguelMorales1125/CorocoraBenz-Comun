# CB-Comun (`cb-comun`)

Librería compartida de Corocora Benz: credencial interna entre sistemas, cliente de la REST del motor, sesión de los portales, formato común de errores y recursos web comunes. Es un jar normal: no se ejecuta ni escucha en ningún puerto.

> **Este repositorio es un componente de Corocora Benz. El hub integrador, con la guía general, está en https://github.com/MiguelMorales1125/Corocora-Benz.**

Repositorio de este componente: https://github.com/MiguelMorales1125/CorocoraBenz-Comun

## Propósito

Concentra en un solo lugar las reglas de integración del [contrato](https://github.com/MiguelMorales1125/Corocora-Benz/blob/main/docs/CONTRATO-INTEGRACION.md) para que los seis sistemas las cumplan igual. No atiende ningún carril ni tarea del BPMN: es la pieza con la que los demás componentes hablan con el motor y entre sí. Cada parte es una autoconfiguración de Spring Boot que se activa sola según las propiedades del sistema que la usa:

| Qué aporta | Se activa con | Quién lo usa |
|---|---|---|
| **Credencial interna** (`CredencialInterna`): token del encabezado `X-CB-Internal`, comparación en tiempo constante y un `RestClient` que ya lleva la credencial | `corocora.internal-token` (obligatoria: sin ella la aplicación no arranca) | Los seis sistemas |
| **Filtro de la credencial** (`FiltroCredencialInterna`): responde 401 `{"error":"Credencial interna requerida"}` en las rutas internas; también acepta `Authorization: Bearer` | `corocora.rutas-internas` | Motor (`/engine-rest/*`, `/api/demo/*`), Portal TI (`/api/tickets`, `/api/tickets/*`), Banco y Taller-Bodega (`/api/*`) |
| **Cliente del motor** (`MotorClient`): `get`, `post`, `put`, `delete` sobre la REST de Camunda, `enviarMensaje` (`POST /message` por `businessKey`), `existeInstanciaActiva` y `tipar` (variables al formato `{value, type}` de Camunda) | `corocora.engine-rest` | Portal Cliente, Portal TI, CentralSys, Banco y Taller-Bodega |
| **Sesión de portal** (`SeguridadPortalAutoConfiguration`): inicio de sesión con Spring Security validado contra el motor (`/identity/verify`, `/group?member=`), CSRF en las escrituras y respuestas siempre en JSON, sin redirecciones | `corocora.portal.habilitado=true` y Spring Security en el classpath | Portal Cliente, Portal TI y CentralSys |
| **Errores comunes** (`ErroresApi`): cuerpo `{"error":"mensaje"}` para 400, 403, 404, 409 y 502 (motor caído o rechazo del motor) | Cualquier aplicación web | Los seis sistemas |
| **Recursos web** `auth.js` y `portal.css` (en `META-INF/resources`) | Se sirven en `/auth.js` y `/portal.css` | Las páginas de los tres portales |

Los mensajes de correlación de los procesos (`SolicitudServicio`, `CotizacionAceptada`, `CotizacionRechazada`, `DispersionAceptada`, `DispersionRechazada`, `RepuestosDisponibles`, `RepuestoRecibido`) salen todos por `MotorClient.enviarMensaje`, que devuelve `ENTREGADO`, `NO_ESPERADO` o `MOTOR_NO_DISPONIBLE` para que cada sistema decida si reintenta.

## Puerto y API

No tiene puerto. Aporta estas rutas a los portales que activan la sesión (las atiende el propio portal, en su puerto):

| Método y ruta | Quién la llama | Qué hace |
|---|---|---|
| `GET /api/app/csrf` | Navegador (sin sesión) | Devuelve `{"header":"X-CSRF-TOKEN","token":"..."}` |
| `POST /api/app/login` | Navegador (sin sesión) | Formulario `username`, `password`; 204 si es correcto, 401 si no, 502 si el motor no responde |
| `POST /api/app/logout` | Navegador | Cierra la sesión; 204 |
| `GET /api/app/me` | Navegador | `{"user", "groups", "cliente", "admin"}`; 401 sin sesión |
| cualquier otra `/api/app/**` | Navegador | Exige sesión (401 `{"error":"Inicia sesión"}`) y, si no es una lectura, el encabezado CSRF (403) |
| `GET /auth.js`, `GET /portal.css` | Navegador | Barra de sesión, formulario de inicio de sesión y objeto `CB` (`CB.request`, `CB.post`, `CB.put`, `CB.user`, evento `cb-auth-ready`); estilos comunes |

Perfiles de usuario (`UsuarioPortal`): `cliente` si pertenece al grupo `clientes`, `admin` si pertenece a `camunda-admin`, empleado en cualquier otro caso. `Permisos.exigirCliente`, `exigirEmpleado` y `exigirGrupo` lanzan el 403 en los controladores.

## Datos

No persiste datos de negocio ni tiene base de datos. Los portales no guardan contraseñas: las cuentas y los grupos viven en el motor.

## Configuración

No tiene `application.yaml` propio ni lee variables de entorno directamente. Lee estas propiedades del sistema que la usa, que a su vez las toma de variables de entorno en su `application.yaml`:

| Propiedad | Para qué | Valor en los sistemas |
|---|---|---|
| `corocora.internal-token` | Credencial interna entre sistemas | `${COROCORA_INTERNAL_TOKEN:cb-local-interno-demo}` |
| `corocora.rutas-internas` | Patrones de servlet, separados por comas, que exigen la credencial | Fijo en cada sistema (ver la tabla de arriba) |
| `corocora.engine-rest` | URL base de la REST de Camunda | `${ENGINE_REST_URL:http://localhost:9000/engine-rest}` |
| `corocora.portal.habilitado` | `true` activa la sesión de portal bajo `/api/app` | `true` en los tres portales |

El perfil `h2` no aplica a esta librería: lo define cada sistema para su propia base de datos.

## Compilar y usar

Este repositorio no compila clonado por separado: su `pom.xml` hereda del pom padre del hub (`com.corocorabenz:corocora-benz:1.0.0-SNAPSHOT`), que fija las versiones de Spring Boot. Se compila desde el hub, con Java 17 o superior (Maven no hace falta: el hub trae su wrapper).

```powershell
git clone https://github.com/MiguelMorales1125/Corocora-Benz.git
cd Corocora-Benz/corocora-benz-embebida
.\mvnw.cmd -pl cb-comun -am package -DskipTests
```

En Linux o macOS se usa `./mvnw` en lugar de `.\mvnw.cmd`. El resultado es `cb-comun/target/cb-comun-1.0.0-SNAPSHOT.jar`, que no se ejecuta con `java -jar`: queda empaquetado dentro del jar de cada sistema. No hace falta compilarla aparte, porque al compilar cualquier otro módulo con `-am` Maven la compila primero.

Un sistema la usa declarando la dependencia (la versión la fija el pom padre) y las propiedades de la tabla anterior:

```xml
<dependency>
  <groupId>com.corocorabenz</groupId>
  <artifactId>cb-comun</artifactId>
</dependency>
```

Spring Security es una dependencia opcional de la librería: solo los portales la declaran, y por eso solo ellos tienen sesión. No hay orden de arranque ni URL que comprobar; se comprueba con sus pruebas.

## Pruebas

```powershell
.\mvnw.cmd -pl cb-comun -am test
```

Son 49 pruebas, sin ningún sistema arrancado:

- `SesionPortalTest` (30), sobre una aplicación mínima de prueba (`AplicacionDePrueba`, `RutasDePrueba`) con el motor simulado: inicio y cierre de sesión, perfil según los grupos, CSRF, permisos de cliente, empleado y grupo, formato de los errores (400, 404, 502) y rutas internas que exigen la credencial sin depender de la sesión.
- `MotorClientTest` (13): codificación de rutas y parámetros, conversión de errores a `MotorException` y `MotorNoDisponibleException`, los tres resultados de `enviarMensaje`, `existeInstanciaActiva` y `tipar`.
- `AutoconfiguracionTest` (6): qué se registra según las propiedades (filtro, cliente del motor, sesión) y que la aplicación no arranca sin el token.

## Estructura del código

Paquete base `com.corocorabenz.comun` (`src/main/java`):

- `credencial`: `CredencialInterna`, `FiltroCredencialInterna` y su autoconfiguración.
- `motor`: `MotorClient`, `ResultadoMensaje`, `MotorException`, `MotorNoDisponibleException` y su autoconfiguración.
- `portal`: `SeguridadPortalAutoConfiguration`, `AutenticacionMotor` (valida contra el motor), `SesionController` (`/api/app/csrf` y `/me`), `UsuarioPortal` y `Permisos`.
- `web`: `ErroresApi`, `RespuestaJson` y su autoconfiguración.
- `src/main/resources/META-INF/resources`: `auth.js` y `portal.css`.
- `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: registro de las cuatro autoconfiguraciones.
