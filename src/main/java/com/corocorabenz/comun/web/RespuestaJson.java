package com.corocorabenz.comun.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;

/** Escribe el cuerpo de error común {@code {"error":"mensaje"}} desde filtros, donde no hay Spring MVC. */
public final class RespuestaJson {

  private static final ObjectMapper JSON = new ObjectMapper();

  private RespuestaJson() {
  }

  public static Map<String, String> cuerpo(String mensaje) {
    return Map.of("error", mensaje);
  }

  public static void error(HttpServletResponse response, int status, String mensaje) throws IOException {
    response.setStatus(status);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(JSON.writeValueAsString(cuerpo(mensaje)));
  }
}
