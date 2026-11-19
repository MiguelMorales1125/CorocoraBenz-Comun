package com.corocorabenz.comun.portal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Extremos de sesión comunes a los tres portales (sección 3 del contrato). */
@RestController
@RequestMapping("/api/app")
public class SesionController {

  /** Encabezado y valor que el navegador debe enviar en cada escritura. */
  public record TokenCsrf(String header, String token) {
  }

  @GetMapping("/csrf")
  public TokenCsrf csrf(HttpServletRequest request) {
    CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    // En Spring Security 6 el token es diferido: leer su valor es lo que lo genera y lo guarda en la sesión.
    return new TokenCsrf(token.getHeaderName(), token.getToken());
  }

  @GetMapping("/me")
  public UsuarioPortal me(@AuthenticationPrincipal UsuarioPortal usuario) {
    return usuario;
  }
}
