package com.corocorabenz.comun.motor;

import com.corocorabenz.comun.credencial.CredencialInterna;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

/**
 * Cliente de la REST API de Camunda en CB-BPM-Engine. Es la única puerta al motor
 * desde los demás sistemas: lleva la credencial interna en cada llamada.
 *
 * <p>Las rutas son relativas a {@code corocora.engine-rest} (por ejemplo {@code /task/abc/claim})
 * y los parámetros de consulta van siempre en el mapa, nunca pegados a la ruta.
 */
public class MotorClient {

  private static final Logger LOG = LoggerFactory.getLogger(MotorClient.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int SIN_COINCIDENCIA = 400;

  private final RestClient rest;
  private final String base;

  public MotorClient(CredencialInterna credencial, String engineRest) {
    this(credencial.clienteRest(engineRest), engineRest);
  }

  /** Para pruebas: permite inyectar un RestClient simulado. */
  MotorClient(RestClient rest, String engineRest) {
    this.rest = rest;
    this.base = sinBarraFinal(engineRest);
  }

  public JsonNode get(String path) {
    return get(path, Map.of());
  }

  /**
   * @param parametros parámetros de consulta; los valores nulos se omiten y las colecciones
   *     se envían separadas por comas, como espera Camunda ({@code candidateGroups=g1,g2})
   */
  public JsonNode get(String path, Map<String, ?> parametros) {
    return ejecutar(HttpMethod.GET, path, parametros, null);
  }

  /** @param body objeto que se envía como JSON, o {@code null} para no enviar cuerpo */
  public JsonNode post(String path, Object body) {
    return ejecutar(HttpMethod.POST, path, Map.of(), body);
  }

  public JsonNode put(String path, Object body) {
    return ejecutar(HttpMethod.PUT, path, Map.of(), body);
  }

  public JsonNode delete(String path) {
    return ejecutar(HttpMethod.DELETE, path, Map.of(), null);
  }

  /**
   * Envía un mensaje BPMN al proceso con esa businessKey ({@code POST /message}).
   *
   * @throws MotorException si el motor falla por algo distinto de "nadie espera ese mensaje"
   */
  public ResultadoMensaje enviarMensaje(String mensaje, String businessKey, Map<String, Object> variables) {
    Map<String, Object> cuerpo = new LinkedHashMap<>();
    cuerpo.put("messageName", mensaje);
    cuerpo.put("businessKey", businessKey);
    cuerpo.put("processVariables", tipar(variables));
    try {
      post("/message", cuerpo);
      LOG.info("Mensaje {} entregado al motor para {}", mensaje, businessKey);
      return ResultadoMensaje.ENTREGADO;
    } catch (MotorException e) {
      if (e.getStatus() != SIN_COINCIDENCIA) {
        throw e;
      }
      LOG.debug("Ningún proceso espera {} para {}: {}", mensaje, businessKey, e.getCuerpo());
      return ResultadoMensaje.NO_ESPERADO;
    } catch (MotorNoDisponibleException e) {
      LOG.warn("El motor no respondió al enviar {} para {}", mensaje, businessKey);
      return ResultadoMensaje.MOTOR_NO_DISPONIBLE;
    }
  }

  /**
   * @throws MotorNoDisponibleException si no hay conexión; quien llama decide si reintenta
   */
  public boolean existeInstanciaActiva(String businessKey) {
    Map<String, Object> parametros = new LinkedHashMap<>();
    parametros.put("businessKey", businessKey);
    parametros.put("active", true);
    parametros.put("maxResults", 1);
    JsonNode instancias = get("/process-instance", parametros);
    return instancias != null && instancias.isArray() && !instancias.isEmpty();
  }

  /**
   * Convierte valores de Java al formato de variables de Camunda:
   * {@code {nombre: {value, type}}}. Los enteros viajan como Long y los decimales como Double.
   */
  public static Map<String, Map<String, Object>> tipar(Map<String, Object> variables) {
    Map<String, Map<String, Object>> tipadas = new LinkedHashMap<>();
    if (variables == null) {
      return tipadas;
    }
    for (Map.Entry<String, Object> variable : variables.entrySet()) {
      tipadas.put(variable.getKey(), tiparValor(variable.getValue()));
    }
    return tipadas;
  }

  private static Map<String, Object> tiparValor(Object valor) {
    Map<String, Object> tipada = new LinkedHashMap<>();
    if (valor == null) {
      tipada.put("value", null);
      tipada.put("type", "Null");
    } else if (valor instanceof Boolean) {
      tipada.put("value", valor);
      tipada.put("type", "Boolean");
    } else if (esEntero(valor)) {
      tipada.put("value", ((Number) valor).longValue());
      tipada.put("type", "Long");
    } else if (valor instanceof Number numero) {
      tipada.put("value", numero.doubleValue());
      tipada.put("type", "Double");
    } else {
      tipada.put("value", valor.toString());
      tipada.put("type", "String");
    }
    return tipada;
  }

  private static boolean esEntero(Object valor) {
    return valor instanceof Byte
        || valor instanceof Short
        || valor instanceof Integer
        || valor instanceof Long
        || valor instanceof BigInteger;
  }

  private JsonNode ejecutar(HttpMethod metodo, String path, Map<String, ?> parametros, Object body) {
    URI uri = uri(path, parametros);
    RestClient.RequestBodySpec peticion = rest.method(metodo)
        .uri(uri)
        .accept(MediaType.APPLICATION_JSON);
    if (body != null) {
      peticion.contentType(MediaType.APPLICATION_JSON);
      peticion.body(body);
    }
    try {
      byte[] respuesta = peticion.retrieve()
          .onStatus(HttpStatusCode::isError, (request, response) -> {
            throw new MotorException(response.getStatusCode().value(), leerCuerpo(response));
          })
          .body(byte[].class);
      return aJson(respuesta, metodo, path);
    } catch (ResourceAccessException e) {
      throw new MotorNoDisponibleException("El motor no responde en " + base, e);
    }
  }

  /**
   * Arma la URL codificando cada segmento y cada parámetro por separado. Así una
   * businessKey con espacios, tildes, {@code &} o {@code +} llega intacta al motor
   * y un identificador que venga del navegador no puede cambiar la ruta ni la consulta.
   */
  private URI uri(String path, Map<String, ?> parametros) {
    StringBuilder url = new StringBuilder(base);
    for (String segmento : path.split("/")) {
      if (segmento.isEmpty()) {
        continue;
      }
      if (segmento.equals(".") || segmento.equals("..")) {
        throw new IllegalArgumentException("Ruta del motor no válida: " + path);
      }
      url.append('/');
      url.append(codificar(segmento));
    }
    char separador = '?';
    for (Map.Entry<String, ?> parametro : parametros.entrySet()) {
      if (parametro.getValue() == null) {
        continue;
      }
      url.append(separador);
      url.append(codificar(parametro.getKey()));
      url.append('=');
      url.append(codificar(comoTexto(parametro.getValue())));
      separador = '&';
    }
    return URI.create(url.toString());
  }

  private static String codificar(String texto) {
    return UriUtils.encode(texto, StandardCharsets.UTF_8);
  }

  private static String comoTexto(Object valor) {
    if (valor instanceof Collection<?> valores) {
      return valores.stream()
          .map(String::valueOf)
          .collect(Collectors.joining(","));
    }
    return String.valueOf(valor);
  }

  private static JsonNode aJson(byte[] respuesta, HttpMethod metodo, String path) {
    if (respuesta == null || respuesta.length == 0) {
      return null;
    }
    try {
      return JSON.readTree(respuesta);
    } catch (IOException e) {
      throw new MotorException(502, "Respuesta que no es JSON en " + metodo + " " + path);
    }
  }

  private static String leerCuerpo(ClientHttpResponse response) {
    try {
      return new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return "";
    }
  }

  private static String sinBarraFinal(String url) {
    String limpia = url.trim();
    while (limpia.endsWith("/")) {
      limpia = limpia.substring(0, limpia.length() - 1);
    }
    return limpia;
  }
}
