package com.corocorabenz.comun.credencial;

import com.corocorabenz.comun.web.RespuestaJson;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Deja pasar solo las peticiones que traen la credencial interna, en
 * {@code X-CB-Internal} o en {@code Authorization: Bearer} (así la envía Camunda Modeler).
 */
public class FiltroCredencialInterna extends OncePerRequestFilter {

  public static final String MENSAJE = "Credencial interna requerida";

  private static final String PREFIJO_BEARER = "Bearer ";

  private final CredencialInterna credencial;

  public FiltroCredencialInterna(CredencialInterna credencial) {
    this.credencial = credencial;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (traeCredencial(request)) {
      chain.doFilter(request, response);
      return;
    }
    RespuestaJson.error(response, HttpServletResponse.SC_UNAUTHORIZED, MENSAJE);
  }

  private boolean traeCredencial(HttpServletRequest request) {
    if (credencial.coincide(request.getHeader(CredencialInterna.HEADER))) {
      return true;
    }
    String autorizacion = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (autorizacion == null) {
      return false;
    }
    boolean esBearer = autorizacion.regionMatches(true, 0, PREFIJO_BEARER, 0, PREFIJO_BEARER.length());
    if (!esBearer) {
      return false;
    }
    return credencial.coincide(autorizacion.substring(PREFIJO_BEARER.length()).trim());
  }
}
