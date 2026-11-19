/*
 * Sesión común de los portales de Corocora Benz (sección 4 del contrato de integración).
 *
 * Cada página declara antes de cargar este archivo:
 *   window.CB_PORTAL = { nombre: "...", perfil: "cliente" | "empleado", cuentas: "texto de ayuda" };
 *
 * Expone el objeto global CB:
 *   CB.request(path)      GET /api/app + path; devuelve el JSON.
 *   CB.post(path, body)   POST con token CSRF y cuerpo JSON.
 *   CB.put(path, body)    PUT con token CSRF y cuerpo JSON.
 *   CB.user               el objeto de /api/app/me, o null.
 *
 * Cuando hay una sesión válida para el portal dispara en window el evento
 * "cb-auth-ready" con detail = CB.user. El navegador solo habla con /api/app
 * de su propio portal: aquí no hay ninguna URL del motor ni credencial interna.
 */
(function () {
  "use strict";

  const API = "/api/app";
  const ID_CONTENEDOR = "cb-sesion";

  const ESTADO_COMPROBANDO = "comprobando";
  const ESTADO_NINGUNA = "ninguna";
  const ESTADO_ACTIVA = "activa";
  const ESTADO_PERFIL_INCORRECTO = "perfil-incorrecto";

  const MENSAJES_POR_ESTADO = {
    400: "La solicitud no es válida.",
    401: "Inicia sesión",
    403: "No tienes permiso para esta operación.",
    404: "No se encontró lo que buscas.",
    409: "La operación no se puede hacer en el estado actual.",
    502: "Otro sistema no responde. Intenta de nuevo en un momento."
  };
  const MENSAJE_GENERICO = "No fue posible completar la operación.";
  const MENSAJE_SIN_CONEXION = "No hay conexión con el portal.";
  // Camunda bloquea la cuenta unos segundos tras un fallo: en ese rato rechaza también la clave correcta.
  const AVISO_DE_ESPERA_TRAS_FALLO = "Si acabas de equivocarte, espera unos segundos antes de volver a intentarlo.";

  const portal = window.CB_PORTAL || {};

  let contenedor = null;
  let estado = ESTADO_COMPROBANDO;

  const CB = {
    user: null,
    request: function (path) {
      return llamar("GET", path);
    },
    post: function (path, body) {
      return llamar("POST", path, body);
    },
    put: function (path, body) {
      return llamar("PUT", path, body);
    },
    cerrarSesion: cerrarSesion
  };
  window.CB = CB;

  // ---------------------------------------------------------------- llamadas

  async function llamar(metodo, path, body) {
    const opciones = {
      method: metodo,
      headers: { Accept: "application/json" }
    };
    if (metodo !== "GET") {
      const csrf = await pedirCsrf();
      opciones.headers[csrf.header] = csrf.token;
      opciones.headers["Content-Type"] = "application/json";
      opciones.body = JSON.stringify(body === undefined ? {} : body);
    }
    const respuesta = await enviar(API + path, opciones);
    if (respuesta.status === 401) {
      alPerderLaSesion();
    }
    return leer(respuesta);
  }

  // El token cambia al iniciar sesión, así que se pide uno nuevo antes de cada escritura.
  async function pedirCsrf() {
    const respuesta = await enviar(API + "/csrf", { headers: { Accept: "application/json" } });
    return leer(respuesta);
  }

  async function enviar(url, opciones) {
    opciones.credentials = "same-origin";
    try {
      return await fetch(url, opciones);
    } catch (fallo) {
      throw new Error(MENSAJE_SIN_CONEXION);
    }
  }

  async function leer(respuesta) {
    const texto = await respuesta.text();
    let datos = null;
    if (texto) {
      try {
        datos = JSON.parse(texto);
      } catch (fallo) {
        datos = null;
      }
    }
    if (respuesta.ok) {
      return datos;
    }
    const error = new Error(mensajeDeError(respuesta.status, datos));
    error.status = respuesta.status;
    throw error;
  }

  function mensajeDeError(status, datos) {
    // El error por defecto de Spring Boot también trae "error", pero en inglés
    // ("Not Found"); se reconoce por su campo "timestamp" y se traduce por el código.
    const esMensajeDelPortal = datos
      && typeof datos.error === "string"
      && datos.error !== ""
      && datos.timestamp === undefined;
    if (esMensajeDelPortal) {
      return datos.error;
    }
    return MENSAJES_POR_ESTADO[status] || MENSAJE_GENERICO;
  }

  // ------------------------------------------------------------------ sesión

  async function consultarUsuario() {
    const respuesta = await enviar(API + "/me", { headers: { Accept: "application/json" } });
    if (respuesta.status === 401) {
      return null;
    }
    return leer(respuesta);
  }

  async function iniciarSesion(usuario, clave) {
    const csrf = await pedirCsrf();
    const opciones = {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/x-www-form-urlencoded"
      },
      body: new URLSearchParams({ username: usuario, password: clave }).toString()
    };
    opciones.headers[csrf.header] = csrf.token;
    const respuesta = await enviar(API + "/login", opciones);
    await leer(respuesta);
  }

  async function cerrarSesion() {
    await llamar("POST", "/logout", {});
    // Recargar borra de la página todo lo que cargó la persona que acaba de salir.
    window.location.reload();
  }

  function correspondeAlPortal(usuario) {
    if (portal.perfil === "cliente") {
      return usuario.cliente === true;
    }
    if (portal.perfil === "empleado") {
      return usuario.cliente !== true || usuario.admin === true;
    }
    return true;
  }

  function alPerderLaSesion() {
    CB.user = null;
    // Antes de la comprobación inicial no hay nada que repintar: ella mostrará el formulario.
    // Y si el formulario ya está a la vista, repintarlo borraría lo que la persona esté escribiendo.
    if (estado === ESTADO_COMPROBANDO || estado === ESTADO_NINGUNA) {
      return;
    }
    pintarLogin("Tu sesión terminó. Inicia sesión de nuevo.", true);
  }

  function alTenerUsuario(usuario) {
    CB.user = usuario;
    if (!correspondeAlPortal(usuario)) {
      pintarAvisoDePerfil(usuario);
      return;
    }
    pintarBarra(usuario);
    window.dispatchEvent(new CustomEvent("cb-auth-ready", { detail: usuario }));
  }

  // ----------------------------------------------------------------- pintado

  function crear(etiqueta, clase, texto) {
    const elemento = document.createElement(etiqueta);
    if (clase) {
      elemento.className = clase;
    }
    if (texto !== undefined) {
      elemento.textContent = texto;
    }
    return elemento;
  }

  function prepararContenedor() {
    contenedor = crear("div", "cb-sesion");
    contenedor.id = ID_CONTENEDOR;
    const destino = document.querySelector("main") || document.body;
    destino.insertBefore(contenedor, destino.firstChild);
  }

  // portal.css oculta el resto de <main> mientras el estado no sea "activa".
  function cambiarEstado(nuevo) {
    estado = nuevo;
    document.documentElement.setAttribute("data-cb-sesion", nuevo);
  }

  function botonCerrarSesion(avisos) {
    const boton = crear("button", "secundario", "Cerrar sesión");
    boton.type = "button";
    boton.addEventListener("click", async function () {
      boton.disabled = true;
      avisos.textContent = "";
      try {
        await cerrarSesion();
      } catch (fallo) {
        avisos.textContent = fallo.message;
        boton.disabled = false;
      }
    });
    return boton;
  }

  function pintarBarra(usuario) {
    const barra = crear("div", "cb-barra");
    barra.setAttribute("role", "group");
    barra.setAttribute("aria-label", "Sesión");

    const quien = crear("span", "cb-barra-usuario", "Sesión: ");
    quien.append(crear("strong", null, usuario.user));

    const grupos = usuario.groups && usuario.groups.length > 0
      ? "Grupos: " + usuario.groups.join(", ")
      : "Sin grupos";

    const avisos = crear("span", "cb-barra-error");
    avisos.setAttribute("role", "alert");

    barra.append(quien, crear("span", "cb-barra-grupos", grupos), botonCerrarSesion(avisos), avisos);
    contenedor.replaceChildren(barra);
    cambiarEstado(ESTADO_ACTIVA);
  }

  function pintarAvisoDePerfil(usuario) {
    const aviso = crear("div", "cb-aviso");
    aviso.setAttribute("role", "alert");

    const destinatarios = portal.perfil === "cliente" ? "clientes" : "empleados";
    const tipoDeCuenta = usuario.cliente ? "de cliente" : "de empleado";
    const nombrePortal = portal.nombre ? portal.nombre : "Este portal";

    const texto = crear("p");
    texto.append(
      nombrePortal + " es para " + destinatarios + ". La cuenta ",
      crear("strong", null, usuario.user),
      " es " + tipoDeCuenta + ": cierra la sesión y entra con otra cuenta."
    );

    const avisos = crear("span", "cb-barra-error");
    aviso.append(texto, botonCerrarSesion(avisos), avisos);
    contenedor.replaceChildren(aviso);
    cambiarEstado(ESTADO_PERFIL_INCORRECTO);
  }

  function campo(etiqueta, atributos) {
    const envoltura = crear("label", null, etiqueta);
    const entrada = document.createElement("input");
    for (const nombre of Object.keys(atributos)) {
      entrada.setAttribute(nombre, atributos[nombre]);
    }
    entrada.required = true;
    envoltura.append(entrada);
    return { envoltura: envoltura, entrada: entrada };
  }

  function pintarLogin(aviso, enfocar) {
    const formulario = crear("form", "cb-login");
    formulario.setAttribute("aria-labelledby", "cb-login-titulo");

    const titulo = crear("h2", null, portal.nombre ? "Iniciar sesión en " + portal.nombre : "Iniciar sesión");
    titulo.id = "cb-login-titulo";

    const usuario = campo("Usuario", {
      name: "username",
      type: "text",
      autocomplete: "username",
      autocapitalize: "none",
      spellcheck: "false"
    });
    const clave = campo("Contraseña", {
      name: "password",
      type: "password",
      autocomplete: "current-password"
    });

    const boton = crear("button", null, "Entrar");
    boton.type = "submit";

    const avisos = crear("p", "cb-login-error", aviso || "");
    avisos.setAttribute("role", "alert");

    formulario.append(titulo, usuario.envoltura, clave.envoltura, boton, avisos);
    if (portal.cuentas) {
      formulario.append(crear("p", "cb-login-ayuda", portal.cuentas));
    }

    formulario.addEventListener("submit", async function (evento) {
      evento.preventDefault();
      boton.disabled = true;
      avisos.textContent = "";
      try {
        await iniciarSesion(usuario.entrada.value.trim(), clave.entrada.value);
        const persona = await consultarUsuario();
        if (!persona) {
          throw new Error("No fue posible iniciar la sesión. Revisa que el navegador acepte cookies.");
        }
        alTenerUsuario(persona);
      } catch (fallo) {
        avisos.textContent = fallo.status === 401
          ? fallo.message + ". " + AVISO_DE_ESPERA_TRAS_FALLO
          : fallo.message;
        clave.entrada.value = "";
        boton.disabled = false;
        clave.entrada.focus();
      }
    });

    contenedor.replaceChildren(formulario);
    cambiarEstado(ESTADO_NINGUNA);
    if (enfocar) {
      usuario.entrada.focus();
    }
  }

  function pintarFalloInicial(mensaje) {
    const aviso = crear("div", "cb-aviso");
    aviso.setAttribute("role", "alert");
    const reintentar = crear("button", null, "Reintentar");
    reintentar.type = "button";
    reintentar.addEventListener("click", comprobarSesion);
    aviso.append(crear("p", null, "No fue posible comprobar la sesión. " + mensaje), reintentar);
    contenedor.replaceChildren(aviso);
  }

  // ------------------------------------------------------------------ inicio

  async function comprobarSesion() {
    cambiarEstado(ESTADO_COMPROBANDO);
    let usuario;
    try {
      usuario = await consultarUsuario();
    } catch (fallo) {
      pintarFalloInicial(fallo.message);
      return;
    }
    if (usuario) {
      alTenerUsuario(usuario);
    } else {
      pintarLogin("", false);
    }
  }

  function iniciar() {
    prepararContenedor();
    comprobarSesion();
  }

  // Se espera al DOM completo para que <main> exista y para que los scripts de la
  // página ya hayan registrado su listener de "cb-auth-ready".
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", iniciar);
  } else {
    iniciar();
  }
})();
