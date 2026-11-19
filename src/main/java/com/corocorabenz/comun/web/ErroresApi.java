package com.corocorabenz.comun.web;

import com.corocorabenz.comun.motor.MotorException;
import com.corocorabenz.comun.motor.MotorNoDisponibleException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Da a los errores de las API el formato común {@code {"error":"mensaje en español"}}.
 *
 * <p>Solo atiende excepciones concretas: los 404 y 405 de rutas inexistentes y cualquier
 * error inesperado siguen el camino normal de Spring Boot. Tiene la prioridad más baja
 * para que un sistema pueda declarar su propio {@code @RestControllerAdvice} y ganar.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ErroresApi {

  public static final String MENSAJE_MOTOR_RECHAZO = "El motor rechazó la operación";
  public static final String MENSAJE_MOTOR_CAIDO = "El motor no responde";
  public static final String MENSAJE_JSON_INVALIDO = "El cuerpo de la petición no es un JSON válido";
  public static final String MENSAJE_DATOS_INVALIDOS = "Los datos enviados no son válidos";

  private static final Logger LOG = LoggerFactory.getLogger(ErroresApi.class);

  /** Incluye {@code ResponseStatusException}, que es como los controladores señalan 400, 403, 404 y 409. */
  @ExceptionHandler(ErrorResponseException.class)
  public ResponseEntity<Map<String, String>> conEstado(ErrorResponseException excepcion) {
    HttpStatusCode estado = excepcion.getStatusCode();
    return ResponseEntity.status(estado)
        .headers(excepcion.getHeaders())
        .body(RespuestaJson.cuerpo(mensajeDe(excepcion, estado)));
  }

  /** Un {@code @Valid @RequestBody} que no cumple sus restricciones. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> cuerpoInvalido(MethodArgumentNotValidException excepcion) {
    // Las restricciones no se evalúan en un orden fijo: se ordena para que
    // los mismos datos produzcan siempre el mismo mensaje.
    String mensaje = excepcion.getBindingResult().getFieldErrors().stream()
        .sorted(Comparator.comparing(FieldError::getField))
        .map(FieldError::getDefaultMessage)
        .findFirst()
        .orElseGet(() -> excepcion.getBindingResult().getAllErrors().stream()
            .map(MessageSourceResolvable::getDefaultMessage)
            .findFirst()
            .orElse(MENSAJE_DATOS_INVALIDOS));
    return solicitudIncorrecta(mensaje);
  }

  /** Restricciones puestas directamente en parámetros del método ({@code @PathVariable}, {@code @RequestParam}). */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<Map<String, String>> parametrosInvalidos(HandlerMethodValidationException excepcion) {
    String mensaje = excepcion.getAllErrors().stream()
        .map(MessageSourceResolvable::getDefaultMessage)
        .findFirst()
        .orElse(MENSAJE_DATOS_INVALIDOS);
    return solicitudIncorrecta(mensaje);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Map<String, String>> restriccionIncumplida(ConstraintViolationException excepcion) {
    String mensaje = excepcion.getConstraintViolations().stream()
        .sorted(Comparator.comparing(violacion -> violacion.getPropertyPath().toString()))
        .map(ConstraintViolation::getMessage)
        .findFirst()
        .orElse(MENSAJE_DATOS_INVALIDOS);
    return solicitudIncorrecta(mensaje);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> cuerpoIlegible(HttpMessageNotReadableException excepcion) {
    return solicitudIncorrecta(MENSAJE_JSON_INVALIDO);
  }

  @ExceptionHandler(MotorException.class)
  public ResponseEntity<Map<String, String>> motorRechazo(MotorException excepcion) {
    LOG.warn("El motor rechazó una operación ({}): {}", excepcion.getStatus(), excepcion.getCuerpo());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(RespuestaJson.cuerpo(MENSAJE_MOTOR_RECHAZO));
  }

  @ExceptionHandler(MotorNoDisponibleException.class)
  public ResponseEntity<Map<String, String>> motorCaido(MotorNoDisponibleException excepcion) {
    LOG.warn("{}", excepcion.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(RespuestaJson.cuerpo(MENSAJE_MOTOR_CAIDO));
  }

  private static ResponseEntity<Map<String, String>> solicitudIncorrecta(String mensaje) {
    String texto = mensaje == null || mensaje.isBlank() ? MENSAJE_DATOS_INVALIDOS : mensaje;
    return ResponseEntity.badRequest().body(RespuestaJson.cuerpo(texto));
  }

  private static String mensajeDe(ErrorResponseException excepcion, HttpStatusCode estado) {
    String mensaje = excepcion instanceof ResponseStatusException conMotivo
        ? conMotivo.getReason()
        : excepcion.getBody().getDetail();
    if (mensaje != null && !mensaje.isBlank()) {
      return mensaje;
    }
    return mensajePorDefecto(estado.value());
  }

  private static String mensajePorDefecto(int estado) {
    return switch (estado) {
      case 400 -> "La solicitud no es válida";
      case 401 -> "Inicia sesión";
      case 403 -> "No tienes permiso para esta operación";
      case 404 -> "No se encontró lo que buscas";
      case 409 -> "La operación no se puede hacer en el estado actual";
      case 502, 503, 504 -> "Otro sistema no responde";
      default -> "No fue posible completar la operación";
    };
  }
}
