package com.corocorabenz.comun.portal;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Comprobaciones de permiso para los controladores de {@code /api/app/**}.
 * Lanzan 403 con un mensaje que {@code ErroresApi} convierte en {@code {"error":"..."}}.
 */
public final class Permisos {

  private Permisos() {
  }

  /** Solo cuentas del grupo {@code clientes}. */
  public static void exigirCliente(UsuarioPortal usuario) {
    exigirSesion(usuario);
    if (!usuario.cliente()) {
      throw prohibido("Esta operación es solo para clientes");
    }
  }

  /** Empleados y administradores; una cuenta de cliente no pasa. */
  public static void exigirEmpleado(UsuarioPortal usuario) {
    exigirSesion(usuario);
    if (!usuario.esEmpleado() && !usuario.admin()) {
      throw prohibido("Esta operación es solo para empleados");
    }
  }

  /** Quien pertenece a ese grupo, o un administrador. */
  public static void exigirGrupo(UsuarioPortal usuario, String grupo) {
    exigirSesion(usuario);
    if (!usuario.perteneceA(grupo) && !usuario.admin()) {
      throw prohibido("Esta operación es solo para el grupo " + grupo);
    }
  }

  private static void exigirSesion(UsuarioPortal usuario) {
    if (usuario == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Inicia sesión");
    }
  }

  private static ResponseStatusException prohibido(String mensaje) {
    return new ResponseStatusException(HttpStatus.FORBIDDEN, mensaje);
  }
}
