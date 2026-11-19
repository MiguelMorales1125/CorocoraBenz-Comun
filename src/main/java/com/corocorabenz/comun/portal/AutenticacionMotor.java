package com.corocorabenz.comun.portal;

import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.motor.MotorException;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * Valida usuario y contraseña contra el servicio de identidad de Camunda y carga sus grupos.
 * Los portales no guardan contraseñas: el motor es la única fuente de cuentas.
 */
public class AutenticacionMotor implements AuthenticationProvider {

  private final MotorClient motor;

  public AutenticacionMotor(MotorClient motor) {
    this.motor = motor;
  }

  @Override
  public Authentication authenticate(Authentication authentication) {
    String usuario = authentication.getName();
    Object clave = authentication.getCredentials();
    if (usuario == null || usuario.isBlank() || clave == null || clave.toString().isEmpty()) {
      throw new BadCredentialsException("Faltan el usuario o la contraseña");
    }
    try {
      if (!verificar(usuario, clave.toString())) {
        throw new BadCredentialsException("Usuario o contraseña incorrectos");
      }
      UsuarioPortal persona = UsuarioPortal.de(usuario, gruposDe(usuario));
      // Los permisos se deciden con los grupos del UsuarioPortal, no con autoridades de Spring Security.
      return UsernamePasswordAuthenticationToken.authenticated(persona, null, AuthorityUtils.NO_AUTHORITIES);
    } catch (MotorNoDisponibleException | MotorException e) {
      throw new AuthenticationServiceException("El motor no responde", e);
    }
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
  }

  private boolean verificar(String usuario, String clave) {
    Map<String, String> credenciales = new LinkedHashMap<>();
    credenciales.put("username", usuario);
    credenciales.put("password", clave);
    JsonNode respuesta = motor.post("/identity/verify", credenciales);
    return respuesta != null && respuesta.path("authenticated").asBoolean(false);
  }

  private List<String> gruposDe(String usuario) {
    JsonNode respuesta = motor.get("/group", Map.of("member", usuario));
    List<String> grupos = new ArrayList<>();
    if (respuesta == null) {
      return grupos;
    }
    for (JsonNode grupo : respuesta) {
      grupos.add(grupo.path("id").asText());
    }
    return grupos;
  }
}
