package com.corocorabenz.pruebacomun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corocorabenz.comun.credencial.CredencialInterna;
import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Sesión, CSRF, permisos, errores y credencial interna vistos desde fuera,
 * con el motor simulado. Cubre las secciones 2 y 3 del contrato.
 */
@SpringBootTest(properties = {
    "corocora.internal-token=token-de-prueba",
    "corocora.rutas-internas=/interna/*",
    "corocora.engine-rest=http://motor.test/engine-rest",
    "corocora.portal.habilitado=true"
})
@AutoConfigureMockMvc
class SesionPortalTest {

  private static final String TOKEN = "token-de-prueba";
  private static final String CLAVE = "demo";
  private static final Map<String, List<String>> GRUPOS = Map.of(
      "mesa1", List.of("mesa-servicio"),
      "cliente1", List.of("clientes"),
      "bodega1", List.of("bodega"),
      "demo", List.of("camunda-admin"),
      "USR-01", List.of());

  private final ObjectMapper json = new ObjectMapper();

  @Autowired
  private MockMvc mvc;

  @MockitoBean
  private MotorClient motor;

  @BeforeEach
  void simularMotor() {
    when(motor.post(eq("/identity/verify"), any())).thenAnswer(llamada -> {
      Map<?, ?> credenciales = llamada.getArgument(1);
      boolean correcta = GRUPOS.containsKey(credenciales.get("username"))
          && CLAVE.equals(credenciales.get("password"));
      return json.createObjectNode().put("authenticated", correcta);
    });
    when(motor.get(eq("/group"), any())).thenAnswer(llamada -> {
      Map<?, ?> parametros = llamada.getArgument(1);
      ArrayNode grupos = json.createArrayNode();
      for (String grupo : GRUPOS.getOrDefault(parametros.get("member"), List.of())) {
        grupos.addObject().put("id", grupo).put("name", grupo).put("type", "WORKFLOW");
      }
      return grupos;
    });
  }

  // ------------------------------------------------------------------ sesión

  @Test
  void meSinSesionResponde401EnJson() throws Exception {
    mvc.perform(get("/api/app/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().encoding(StandardCharsets.UTF_8))
        .andExpect(jsonPath("$.error").value("Inicia sesión"));
  }

  @Test
  void cualquierOtraRutaDeLaApiSinSesionResponde401() throws Exception {
    mvc.perform(get("/api/app/solo-empleados"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Inicia sesión"));
    mvc.perform(get("/api/app/no-existe"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void csrfEsPublicoYDevuelveEncabezadoYToken() throws Exception {
    mvc.perform(get("/api/app/csrf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.header").value("X-CSRF-TOKEN"))
        .andExpect(jsonPath("$.token").isNotEmpty());
  }

  @Test
  void loginCorrectoResponde204YLuegoMeDevuelveElUsuario() throws Exception {
    MockHttpSession sesion = new MockHttpSession();

    mvc.perform(login(sesion, "mesa1", CLAVE))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    MvcResult me = mvc.perform(get("/api/app/me").session(sesion))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode usuario = json.readTree(me.getResponse().getContentAsString(StandardCharsets.UTF_8));
    JsonNode esperado = json.readTree(
        "{\"user\":\"mesa1\",\"groups\":[\"mesa-servicio\"],\"cliente\":false,\"admin\":false}");
    assertThat(usuario).isEqualTo(esperado);
  }

  @Test
  void elPerfilSaleDeLosGrupos() throws Exception {
    mvc.perform(get("/api/app/me").session(sesionDe("cliente1")))
        .andExpect(jsonPath("$.cliente").value(true))
        .andExpect(jsonPath("$.admin").value(false));
    mvc.perform(get("/api/app/me").session(sesionDe("demo")))
        .andExpect(jsonPath("$.cliente").value(false))
        .andExpect(jsonPath("$.admin").value(true));
    mvc.perform(get("/api/app/me").session(sesionDe("USR-01")))
        .andExpect(jsonPath("$.groups").isEmpty())
        .andExpect(jsonPath("$.cliente").value(false));
  }

  @Test
  void loginIncorrectoResponde401YConsultaAlMotorUnaSolaVez() throws Exception {
    MockHttpSession sesion = new MockHttpSession();

    mvc.perform(login(sesion, "mesa1", "otra-clave"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Usuario o contraseña incorrectos"));

    verify(motor, times(1)).post(eq("/identity/verify"), any());
    mvc.perform(get("/api/app/me").session(sesion))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void loginSinUsuarioResponde401SinLlamarAlMotor() throws Exception {
    mvc.perform(login(new MockHttpSession(), "", ""))
        .andExpect(status().isUnauthorized());

    verify(motor, times(0)).post(any(), any());
  }

  @Test
  void loginConElMotorCaidoResponde502() throws Exception {
    when(motor.post(eq("/identity/verify"), any()))
        .thenThrow(new MotorNoDisponibleException("sin conexión", null));

    mvc.perform(login(new MockHttpSession(), "mesa1", CLAVE))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("El motor no responde"));
  }

  @Test
  void loginSinCsrfResponde403() throws Exception {
    mvc.perform(post("/api/app/login")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("username", "mesa1")
            .param("password", CLAVE))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").isNotEmpty());
  }

  @Test
  void logoutResponde204SinCuerpoYTerminaLaSesion() throws Exception {
    MockHttpSession sesion = sesionDe("mesa1");

    mvc.perform(conCsrf(post("/api/app/logout"), sesion))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    mvc.perform(get("/api/app/me").session(sesion))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void nuncaRedirigeNiGeneraPaginaDeLogin() throws Exception {
    mvc.perform(get("/login"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/app/me").accept(MediaType.TEXT_HTML))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
  }

  // -------------------------------------------------------------------- CSRF

  @Test
  void escrituraSinCsrfResponde403AunqueHayaSesion() throws Exception {
    mvc.perform(post("/api/app/notas")
            .session(sesionDe("mesa1"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"texto\":\"hola\",\"cantidad\":2}"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error").isNotEmpty());
  }

  @Test
  void escrituraConCsrfPasa() throws Exception {
    MockHttpSession sesion = sesionDe("mesa1");

    mvc.perform(conCsrf(post("/api/app/notas"), sesion)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"texto\":\"hola\",\"cantidad\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.texto").value("hola"));
  }

  @Test
  void elTokenCsrfCambiaAlIniciarSesion() throws Exception {
    MockHttpSession sesion = new MockHttpSession();
    JsonNode anterior = pedirCsrf(sesion);
    mvc.perform(post("/api/app/login")
            .session(sesion)
            .header(anterior.get("header").asText(), anterior.get("token").asText())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("username", "mesa1")
            .param("password", CLAVE))
        .andExpect(status().isNoContent());

    mvc.perform(post("/api/app/notas")
            .session(sesion)
            .header(anterior.get("header").asText(), anterior.get("token").asText())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"texto\":\"hola\",\"cantidad\":2}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void laRutaConUnCaracterCodificadoTambienPideCsrf() throws Exception {
    MockHttpSession sesion = sesionDe("mesa1");

    // URI.create conserva el %61; post(String) lo volvería a codificar como %2561.
    mvc.perform(post(URI.create("/api/%61pp/logout")).session(sesion))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Falta el token CSRF o ya no es válido"));

    mvc.perform(get("/api/app/me").session(sesion))
        .andExpect(status().isOk());
  }

  // ---------------------------------------------------------------- permisos

  @Test
  void unClienteNoPasaDondeSeExigeEmpleado() throws Exception {
    mvc.perform(get("/api/app/solo-empleados").session(sesionDe("cliente1")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Esta operación es solo para empleados"));
    mvc.perform(get("/api/app/solo-clientes").session(sesionDe("cliente1")))
        .andExpect(status().isOk());
  }

  @Test
  void unEmpleadoNoPasaDondeSeExigeCliente() throws Exception {
    mvc.perform(get("/api/app/solo-clientes").session(sesionDe("mesa1")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Esta operación es solo para clientes"));
    mvc.perform(get("/api/app/solo-empleados").session(sesionDe("mesa1")))
        .andExpect(status().isOk());
  }

  @Test
  void elGrupoSeExigeSalvoAlAdministrador() throws Exception {
    mvc.perform(get("/api/app/solo-bodega").session(sesionDe("mesa1")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/app/solo-bodega").session(sesionDe("bodega1")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/app/solo-bodega").session(sesionDe("demo")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/app/solo-empleados").session(sesionDe("demo")))
        .andExpect(status().isOk());
  }

  // ----------------------------------------------------------------- errores

  @Test
  void validacionResponde400ConElMensajeDeLaRestriccion() throws Exception {
    MockHttpSession sesion = sesionDe("mesa1");

    mvc.perform(conCsrf(post("/api/app/notas"), sesion)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"texto\":\"\",\"cantidad\":2}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("El texto es obligatorio"));
  }

  @Test
  void conVariosCamposInvalidosElMensajeEsSiempreElMismo() throws Exception {
    MockHttpSession sesion = sesionDe("mesa1");

    for (int intento = 0; intento < 5; intento++) {
      mvc.perform(conCsrf(post("/api/app/notas"), sesion)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"texto\":\"\",\"cantidad\":0}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("La cantidad debe ser al menos 1"));
    }
  }

  @Test
  void jsonIlegibleResponde400() throws Exception {
    MockHttpSession sesion = sesionDe("mesa1");

    mvc.perform(conCsrf(post("/api/app/notas"), sesion)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{esto no es json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("El cuerpo de la petición no es un JSON válido"));
  }

  @Test
  void responseStatusExceptionConservaEstadoYMensaje() throws Exception {
    mvc.perform(get("/api/app/conflicto").session(sesionDe("mesa1")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("Otra persona ya tiene la tarea"));
  }

  @Test
  void losErroresDelMotorSon502SinFiltrarSuDetalle() throws Exception {
    MockHttpSession sesion = sesionDe("mesa1");

    mvc.perform(get("/api/app/motor-rechaza").session(sesion))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("El motor rechazó la operación"));
    mvc.perform(get("/api/app/motor-caido").session(sesion))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("El motor no responde"));
  }

  @Test
  void unaRutaInexistenteSigueSiendoUn404NormalDeSpring() throws Exception {
    mvc.perform(get("/api/app/no-existe").session(sesionDe("mesa1")))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/app/notas").session(sesionDe("mesa1")))
        .andExpect(status().isMethodNotAllowed());
  }

  // ------------------------------------------------------ credencial interna

  @Test
  void rutaInternaSinCredencialResponde401() throws Exception {
    mvc.perform(get("/interna/ping"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.error").value("Credencial interna requerida"));
  }

  @Test
  void rutaInternaConCredencialIncorrectaResponde401() throws Exception {
    mvc.perform(get("/interna/ping").header(CredencialInterna.HEADER, "otro-token"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/interna/ping").header(HttpHeaders.AUTHORIZATION, "Bearer otro-token"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/interna/ping").header(HttpHeaders.AUTHORIZATION, "Basic " + TOKEN))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rutaInternaConCredencialPasa() throws Exception {
    mvc.perform(get("/interna/ping").header(CredencialInterna.HEADER, TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));
    mvc.perform(get("/interna/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
        .andExpect(status().isOk());
  }

  @Test
  void laSesionDeUnPortalNoAbreUnaRutaInterna() throws Exception {
    mvc.perform(get("/interna/ping").session(sesionDe("demo")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rutaInternaAceptaEscriturasSinSesionNiCsrf() throws Exception {
    mvc.perform(post("/interna/eco")
            .header(CredencialInterna.HEADER, TOKEN)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"texto\":\"hola\",\"cantidad\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.texto").value("hola"));
  }

  // ------------------------------------------------------- recursos públicos

  @Test
  void paginasYRecursosComunesNoPidenSesion() throws Exception {
    mvc.perform(get("/publica"))
        .andExpect(status().isOk());
    MvcResult authJs = mvc.perform(get("/auth.js"))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(authJs.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .contains("cb-auth-ready")
        .doesNotContain("engine-rest")
        .doesNotContain(CredencialInterna.HEADER);
    mvc.perform(get("/portal.css"))
        .andExpect(status().isOk());
  }

  // ------------------------------------------------------------------ ayudas

  private JsonNode pedirCsrf(MockHttpSession sesion) throws Exception {
    MvcResult resultado = mvc.perform(get("/api/app/csrf").session(sesion))
        .andExpect(status().isOk())
        .andReturn();
    return json.readTree(resultado.getResponse().getContentAsString());
  }

  /** Igual que auth.js: pide un token nuevo justo antes de cada escritura. */
  private MockHttpServletRequestBuilder conCsrf(MockHttpServletRequestBuilder peticion, MockHttpSession sesion)
      throws Exception {
    JsonNode csrf = pedirCsrf(sesion);
    return peticion
        .session(sesion)
        .header(csrf.get("header").asText(), csrf.get("token").asText());
  }

  private MockHttpServletRequestBuilder login(MockHttpSession sesion, String usuario, String clave)
      throws Exception {
    return conCsrf(post("/api/app/login"), sesion)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .param("username", usuario)
        .param("password", clave);
  }

  private MockHttpSession sesionDe(String usuario) throws Exception {
    MockHttpSession sesion = new MockHttpSession();
    mvc.perform(login(sesion, usuario, CLAVE))
        .andExpect(status().isNoContent());
    return sesion;
  }
}
