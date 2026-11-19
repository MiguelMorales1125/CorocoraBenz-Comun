package com.corocorabenz.comun.credencial;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Registra la credencial interna y, si el sistema declara {@code corocora.rutas-internas},
 * el filtro que la exige en esas rutas.
 */
@AutoConfiguration
public class CredencialInternaAutoConfiguration {

  /** Antes que Spring Security (orden -100): una ruta interna nunca depende de la sesión de un portal. */
  public static final int ORDEN_FILTRO = Ordered.HIGHEST_PRECEDENCE + 10;

  @Bean
  @ConditionalOnMissingBean
  CredencialInterna credencialInterna(@Value("${corocora.internal-token:}") String token) {
    return new CredencialInterna(token);
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnProperty("corocora.rutas-internas")
  FilterRegistrationBean<FiltroCredencialInterna> filtroCredencialInterna(
      CredencialInterna credencial, @Value("${corocora.rutas-internas}") String rutas) {
    List<String> patrones = Arrays.stream(rutas.split(","))
        .map(String::trim)
        .filter(patron -> !patron.isEmpty())
        .toList();
    FilterRegistrationBean<FiltroCredencialInterna> registro =
        new FilterRegistrationBean<>(new FiltroCredencialInterna(credencial));
    registro.setName("filtroCredencialInterna");
    registro.setOrder(ORDEN_FILTRO);
    registro.setUrlPatterns(patrones);
    // Un registro sin patrones se aplicaría a todas las rutas y dejaría sin servicio las páginas del portal.
    registro.setEnabled(!patrones.isEmpty());
    return registro;
  }
}
