package com.corocorabenz.pruebacomun;

import com.corocorabenz.comun.motor.MotorException;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import com.corocorabenz.comun.portal.Permisos;
import com.corocorabenz.comun.portal.UsuarioPortal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Rutas de ejemplo: una interna (como las que llama el motor) y varias de portal. */
@RestController
public class RutasDePrueba {

  public record Nota(
      @NotBlank(message = "El texto es obligatorio") String texto,
      @Min(value = 1, message = "La cantidad debe ser al menos 1") int cantidad) {
  }

  @GetMapping("/interna/ping")
  public Map<String, Boolean> ping() {
    return Map.of("ok", true);
  }

  @PostMapping("/interna/eco")
  public Nota ecoInterno(@RequestBody Nota nota) {
    return nota;
  }

  @GetMapping("/publica")
  public Map<String, Boolean> publica() {
    return Map.of("publica", true);
  }

  @PostMapping("/api/app/notas")
  public Nota crearNota(@Valid @RequestBody Nota nota) {
    return nota;
  }

  @GetMapping("/api/app/solo-clientes")
  public Map<String, String> soloClientes(@AuthenticationPrincipal UsuarioPortal usuario) {
    Permisos.exigirCliente(usuario);
    return Map.of("user", usuario.user());
  }

  @GetMapping("/api/app/solo-empleados")
  public Map<String, String> soloEmpleados(@AuthenticationPrincipal UsuarioPortal usuario) {
    Permisos.exigirEmpleado(usuario);
    return Map.of("user", usuario.user());
  }

  @GetMapping("/api/app/solo-bodega")
  public Map<String, String> soloBodega(@AuthenticationPrincipal UsuarioPortal usuario) {
    Permisos.exigirGrupo(usuario, UsuarioPortal.GRUPO_BODEGA);
    return Map.of("user", usuario.user());
  }

  @GetMapping("/api/app/conflicto")
  public void conflicto() {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "Otra persona ya tiene la tarea");
  }

  @GetMapping("/api/app/motor-rechaza")
  public void motorRechaza() {
    throw new MotorException(500, "{\"type\":\"ProcessEngineException\",\"message\":\"detalle interno\"}");
  }

  @GetMapping("/api/app/motor-caido")
  public void motorCaido() {
    throw new MotorNoDisponibleException("El motor no responde en http://motor.test", null);
  }
}
