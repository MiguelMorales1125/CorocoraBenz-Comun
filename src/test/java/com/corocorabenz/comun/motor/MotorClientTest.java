package com.corocorabenz.comun.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.corocorabenz.comun.credencial.CredencialInterna;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** El cliente del motor contra un servidor simulado: URLs, cuerpos y traducción de errores. */
class MotorClientTest {

  private static final String BASE = "http://motor.test/engine-rest";
  private static final String ERROR_CAMUNDA = "{\"type\":\"RestException\",\"message\":\"detalle\"}";

  private MockRestServiceServer servidor;
  private MotorClient motor;

  @BeforeEach
  void preparar() {
    RestClient.Builder constructor = RestClient.builder()
        .defaultHeader(CredencialInterna.HEADER, "token-de-prueba");
    servidor = MockRestServiceServer.bindTo(constructor).build();
    // La barra final sobra a propósito: la URL configurada puede traerla o no.
    motor = new MotorClient(constructor.build(), BASE + "/");
  }

  @Test
  void getCodificaTildesEspaciosYSeparadores() {
    servidor.expect(requestTo(BASE + "/task?processInstanceBusinessKey=PV-%C3%81%20B%26x%3D1%2B2&candidateGroups=g1%2Cg2"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(CredencialInterna.HEADER, "token-de-prueba"))
        .andRespond(withSuccess("[{\"id\":\"t1\"}]", MediaType.APPLICATION_JSON));

    Map<String, Object> parametros = new LinkedHashMap<>();
    parametros.put("processInstanceBusinessKey", "PV-Á B&x=1+2");
    parametros.put("candidateGroups", List.of("g1", "g2"));
    parametros.put("assignee", null);
    JsonNode tareas = motor.get("/task", parametros);

    assertThat(tareas.get(0).get("id").asText()).isEqualTo("t1");
    servidor.verify();
  }

  @Test
  void unIdentificadorNoPuedeCambiarLaRutaNiLaConsulta() {
    servidor.expect(requestTo(BASE + "/task/a%20b%3Fx%3D1%7Bid%7D/claim"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withNoContent());

    motor.post("/task/" + "a b?x=1{id}" + "/claim", Map.of("userId", "mesa1"));

    servidor.verify();
    assertThatThrownBy(() -> motor.get("/task/../process-instance"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void respuestaSinContenidoEsNull() {
    servidor.expect(requestTo(BASE + "/task/t1/unclaim"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withNoContent());

    assertThat(motor.post("/task/t1/unclaim", null)).isNull();
  }

  @Test
  void postYPutEnvianElCuerpoComoJson() {
    servidor.expect(requestTo(BASE + "/process-definition/key/CB_GestionNomina/start"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.businessKey").value("NOM-2026-11"))
        .andRespond(withSuccess("{\"id\":\"p1\",\"businessKey\":\"NOM-2026-11\"}", MediaType.APPLICATION_JSON));
    servidor.expect(requestTo(BASE + "/task/t1/variables/nota"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(jsonPath("$.value").value("revisar"))
        .andRespond(withNoContent());
    servidor.expect(requestTo(BASE + "/task/t1"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withNoContent());

    JsonNode instancia = motor.post("/process-definition/key/CB_GestionNomina/start",
        Map.of("businessKey", "NOM-2026-11"));
    motor.put("/task/t1/variables/nota", Map.of("value", "revisar", "type", "String"));
    motor.delete("/task/t1");

    assertThat(instancia.get("id").asText()).isEqualTo("p1");
    servidor.verify();
  }

  @Test
  void unErrorDelMotorSeConvierteEnMotorExceptionConEstadoYCuerpo() {
    servidor.expect(requestTo(BASE + "/task/no-existe"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body(ERROR_CAMUNDA).contentType(MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> motor.get("/task/no-existe"))
        .isInstanceOfSatisfying(MotorException.class, excepcion -> {
          assertThat(excepcion.getStatus()).isEqualTo(404);
          assertThat(excepcion.getCuerpo()).isEqualTo(ERROR_CAMUNDA);
        });
  }

  @Test
  void sinConexionSeConvierteEnMotorNoDisponible() {
    servidor.expect(requestTo(BASE + "/task"))
        .andRespond(withException(new IOException("conexión rechazada")));

    assertThatThrownBy(() -> motor.get("/task"))
        .isInstanceOf(MotorNoDisponibleException.class);
  }

  @Test
  void unMotorApagadoSeConvierteEnMotorNoDisponibleConElClienteReal() {
    // En el puerto 1 de la máquina local no escucha nadie: la conexión se rechaza sin abrir nada.
    MotorClient apagado = new MotorClient(
        new CredencialInterna("token-de-prueba"), "http://127.0.0.1:1/engine-rest");

    assertThatThrownBy(() -> apagado.get("/task"))
        .isInstanceOf(MotorNoDisponibleException.class);
  }

  @Test
  void enviarMensajeEntregadoLlevaLasVariablesTipadas() {
    servidor.expect(requestTo(BASE + "/message"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.messageName").value("SolicitudServicio"))
        .andExpect(jsonPath("$.businessKey").value("PV-12345678"))
        .andExpect(jsonPath("$.processVariables.mileageKm.value").value(42000))
        .andExpect(jsonPath("$.processVariables.mileageKm.type").value("Long"))
        .andExpect(jsonPath("$.processVariables.serviceType.value").value("Garantía"))
        .andExpect(jsonPath("$.processVariables.serviceType.type").value("String"))
        .andRespond(withNoContent());

    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("mileageKm", 42000);
    variables.put("serviceType", "Garantía");
    ResultadoMensaje resultado = motor.enviarMensaje("SolicitudServicio", "PV-12345678", variables);

    assertThat(resultado).isEqualTo(ResultadoMensaje.ENTREGADO);
    servidor.verify();
  }

  @Test
  void enviarMensajeQueNadieEsperaEsNoEsperado() {
    servidor.expect(requestTo(BASE + "/message"))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(ERROR_CAMUNDA).contentType(MediaType.APPLICATION_JSON));

    assertThat(motor.enviarMensaje("CotizacionAceptada", "PV-1", Map.of()))
        .isEqualTo(ResultadoMensaje.NO_ESPERADO);
  }

  @Test
  void enviarMensajeSinConexionEsMotorNoDisponible() {
    servidor.expect(requestTo(BASE + "/message"))
        .andRespond(withException(new IOException("conexión rechazada")));

    assertThat(motor.enviarMensaje("CotizacionAceptada", "PV-1", null))
        .isEqualTo(ResultadoMensaje.MOTOR_NO_DISPONIBLE);
  }

  @Test
  void enviarMensajeConOtroErrorDelMotorLanzaMotorException() {
    servidor.expect(requestTo(BASE + "/message"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body(ERROR_CAMUNDA));

    assertThatThrownBy(() -> motor.enviarMensaje("CotizacionAceptada", "PV-1", Map.of()))
        .isInstanceOfSatisfying(MotorException.class,
            excepcion -> assertThat(excepcion.getStatus()).isEqualTo(500));
  }

  @Test
  void existeInstanciaActivaSegunLaListaDelMotor() {
    servidor.expect(requestTo(BASE + "/process-instance?businessKey=NOM-1&active=true&maxResults=1"))
        .andRespond(withSuccess("[{\"id\":\"p1\"}]", MediaType.APPLICATION_JSON));
    servidor.expect(requestTo(BASE + "/process-instance?businessKey=NOM-2&active=true&maxResults=1"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    assertThat(motor.existeInstanciaActiva("NOM-1")).isTrue();
    assertThat(motor.existeInstanciaActiva("NOM-2")).isFalse();
  }

  @Test
  void tiparUsaLosTiposDeCamunda() {
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("aprobado", true);
    variables.put("equipos", 3);
    variables.put("kilometros", 42000L);
    variables.put("costo", 1250.5);
    variables.put("monto", new BigDecimal("10.25"));
    variables.put("nota", "revisar");
    variables.put("vacio", null);

    Map<String, Map<String, Object>> tipadas = MotorClient.tipar(variables);

    assertThat(tipadas.get("aprobado")).containsEntry("value", true).containsEntry("type", "Boolean");
    assertThat(tipadas.get("equipos")).containsEntry("value", 3L).containsEntry("type", "Long");
    assertThat(tipadas.get("kilometros")).containsEntry("value", 42000L).containsEntry("type", "Long");
    assertThat(tipadas.get("costo")).containsEntry("value", 1250.5).containsEntry("type", "Double");
    assertThat(tipadas.get("monto")).containsEntry("value", 10.25).containsEntry("type", "Double");
    assertThat(tipadas.get("nota")).containsEntry("value", "revisar").containsEntry("type", "String");
    assertThat(tipadas.get("vacio")).containsEntry("value", null).containsEntry("type", "Null");
    assertThat(MotorClient.tipar(null)).isEmpty();
  }
}
