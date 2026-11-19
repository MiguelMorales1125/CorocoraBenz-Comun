package com.corocorabenz.comun.credencial;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Credencial con la que los sistemas de Corocora Benz se identifican entre sí
 * (sección 2 del contrato de integración). Nunca debe llegar al navegador.
 */
public class CredencialInterna {

  /** Encabezado HTTP que lleva la credencial. */
  public static final String HEADER = "X-CB-Internal";

  private static final Duration TIEMPO_CONEXION = Duration.ofSeconds(3);
  private static final Duration TIEMPO_RESPUESTA = Duration.ofSeconds(30);

  private final String token;
  private final byte[] huellaToken;

  public CredencialInterna(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException(
          "Falta la propiedad corocora.internal-token (ver docs/CONTRATO-INTEGRACION.md, sección 2)");
    }
    this.token = token;
    this.huellaToken = huella(token);
  }

  public String token() {
    return token;
  }

  /**
   * Compara en tiempo constante. Se comparan las huellas SHA-256 y no los textos
   * para que el tiempo tampoco dependa de la longitud de lo que envía el llamador.
   */
  public boolean coincide(String candidato) {
    if (candidato == null || candidato.isEmpty()) {
      return false;
    }
    return MessageDigest.isEqual(huellaToken, huella(candidato));
  }

  /**
   * Cliente REST hacia otro sistema de Corocora Benz, con la credencial interna ya puesta.
   *
   * @param baseUrl URL base del otro sistema, por ejemplo {@code http://localhost:9005}
   */
  public RestClient clienteRest(String baseUrl) {
    // HTTP/1.1 fijo: el cliente del JDK intenta subir a HTTP/2 en claro y Tomcat no lo necesita.
    HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(TIEMPO_CONEXION)
        .build();
    JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(http);
    fabrica.setReadTimeout(TIEMPO_RESPUESTA);
    return RestClient.builder()
        .requestFactory(fabrica)
        .baseUrl(baseUrl)
        .defaultHeader(HEADER, token)
        .build();
  }

  private static byte[] huella(String texto) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no está disponible en esta JVM", e);
    }
  }
}
