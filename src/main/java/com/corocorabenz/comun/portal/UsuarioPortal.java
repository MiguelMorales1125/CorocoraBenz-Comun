package com.corocorabenz.comun.portal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.Serializable;
import java.security.Principal;
import java.util.Collection;
import java.util.List;

/**
 * Persona con sesión en un portal. Es el principal de Spring Security (se recibe en los
 * controladores con {@code @AuthenticationPrincipal UsuarioPortal usuario}) y también
 * el cuerpo de {@code GET /api/app/me}.
 *
 * @param user identificador del usuario en Camunda
 * @param groups identificadores de sus grupos en Camunda
 * @param cliente pertenece al grupo {@code clientes}
 * @param admin pertenece al grupo {@code camunda-admin}
 */
public record UsuarioPortal(String user, List<String> groups, boolean cliente, boolean admin)
    implements Principal, Serializable {

  public static final String GRUPO_CLIENTES = "clientes";
  public static final String GRUPO_ADMIN = "camunda-admin";
  public static final String GRUPO_BODEGA = "bodega";

  public UsuarioPortal {
    groups = List.copyOf(groups);
  }

  /** Crea el usuario deduciendo su perfil a partir de los grupos. */
  public static UsuarioPortal de(String user, Collection<String> groups) {
    return new UsuarioPortal(
        user,
        List.copyOf(groups),
        groups.contains(GRUPO_CLIENTES),
        groups.contains(GRUPO_ADMIN));
  }

  public boolean perteneceA(String grupo) {
    return groups.contains(grupo);
  }

  /** Cualquier cuenta que no sea de cliente. */
  public boolean esEmpleado() {
    return !cliente;
  }

  // Sin esto Jackson añadiría un campo "name" al JSON de /api/app/me.
  @JsonIgnore
  @Override
  public String getName() {
    return user;
  }
}
