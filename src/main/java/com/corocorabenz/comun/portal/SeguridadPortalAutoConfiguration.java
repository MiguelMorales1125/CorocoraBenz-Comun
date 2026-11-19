package com.corocorabenz.comun.portal;

import com.corocorabenz.comun.motor.MotorAutoConfiguration;
import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.web.RespuestaJson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Sesión de los portales (sección 3 del contrato). Se activa con
 * {@code corocora.portal.habilitado=true} en los sistemas que llevan Spring Security.
 *
 * <p>La API responde siempre con códigos y JSON; nunca redirige a una página de login.
 */
@AutoConfiguration(
    after = MotorAutoConfiguration.class,
    before = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({SecurityFilterChain.class, HttpSecurity.class})
@ConditionalOnProperty(name = "corocora.portal.habilitado", havingValue = "true")
@EnableWebSecurity
public class SeguridadPortalAutoConfiguration {

  public static final String API = "/api/app";
  public static final String MENSAJE_SIN_SESION = "Inicia sesión";
  public static final String MENSAJE_LOGIN_INCORRECTO = "Usuario o contraseña incorrectos";
  public static final String MENSAJE_MOTOR_CAIDO = "El motor no responde";
  public static final String MENSAJE_SIN_PERMISO = "No tienes permiso para esta operación";
  public static final String MENSAJE_CSRF = "Falta el token CSRF o ya no es válido";

  private static final Set<String> METODOS_DE_LECTURA = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

  @Bean
  AutenticacionMotor autenticacionMotor(MotorClient motor) {
    return new AutenticacionMotor(motor);
  }

  @Bean
  SesionController sesionController() {
    return new SesionController();
  }

  @Bean
  SecurityFilterChain cadenaSeguridadPortal(HttpSecurity http, AutenticacionMotor autenticacion) throws Exception {
    // Un ProviderManager propio y sin padre: así un login fallido consulta al motor una sola vez.
    http.authenticationManager(new ProviderManager(autenticacion));

    http.authorizeHttpRequests(peticiones -> peticiones
        .requestMatchers(API + "/csrf", API + "/login").permitAll()
        .requestMatchers(API + "/**").authenticated()
        // Páginas, estáticos y rutas internas (estas ya las protege el filtro de credencial interna).
        .anyRequest().permitAll());

    http.csrf(csrf -> csrf.requireCsrfProtectionMatcher(escriturasDeLaApi()));

    http.formLogin(login -> login
        .loginProcessingUrl(API + "/login")
        .successHandler(alIniciarSesion())
        .failureHandler(alFallarElLogin()));

    http.logout(logout -> logout
        .logoutUrl(API + "/logout")
        .logoutSuccessHandler(alCerrarSesion()));

    http.exceptionHandling(errores -> errores
        .authenticationEntryPoint(sinSesion())
        .accessDeniedHandler(accesoDenegado()));

    // Sin caché de peticiones: tras el login no hay a dónde "volver", el navegador repite su llamada.
    http.requestCache(RequestCacheConfigurer::disable);

    return http.build();
  }

  /** CSRF solo en escrituras a /api/app/**: las rutas internas las llama el motor, que no tiene sesión. */
  private static RequestMatcher escriturasDeLaApi() {
    return request -> {
      if (METODOS_DE_LECTURA.contains(request.getMethod())) {
        return false;
      }
      return rutaSinContexto(request).startsWith(API + "/");
    };
  }

  /**
   * La ruta ya decodificada, la misma que usan la autorización y los controladores:
   * así {@code /api/%61pp/...} cuenta como {@code /api/app/...} y también pide el token.
   */
  private static String rutaSinContexto(HttpServletRequest request) {
    String resto = request.getPathInfo();
    if (resto == null) {
      return request.getServletPath();
    }
    return request.getServletPath() + resto;
  }

  private static AuthenticationSuccessHandler alIniciarSesion() {
    return (request, response, authentication) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  private static AuthenticationFailureHandler alFallarElLogin() {
    return (request, response, excepcion) -> {
      if (excepcion instanceof AuthenticationServiceException) {
        RespuestaJson.error(response, HttpServletResponse.SC_BAD_GATEWAY, MENSAJE_MOTOR_CAIDO);
        return;
      }
      RespuestaJson.error(response, HttpServletResponse.SC_UNAUTHORIZED, MENSAJE_LOGIN_INCORRECTO);
    };
  }

  private static LogoutSuccessHandler alCerrarSesion() {
    return (request, response, authentication) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }

  private static AuthenticationEntryPoint sinSesion() {
    return (request, response, excepcion) ->
        RespuestaJson.error(response, HttpServletResponse.SC_UNAUTHORIZED, MENSAJE_SIN_SESION);
  }

  private static AccessDeniedHandler accesoDenegado() {
    return (request, response, excepcion) -> {
      String mensaje = excepcion instanceof CsrfException ? MENSAJE_CSRF : MENSAJE_SIN_PERMISO;
      RespuestaJson.error(response, HttpServletResponse.SC_FORBIDDEN, mensaje);
    };
  }
}
