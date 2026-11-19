package com.corocorabenz.comun.motor;

/** El motor respondió con un error (4xx o 5xx). */
public class MotorException extends RuntimeException {

  private final int status;
  private final String cuerpo;

  public MotorException(int status, String cuerpo) {
    super("El motor respondió " + status + ": " + cuerpo);
    this.status = status;
    this.cuerpo = cuerpo;
  }

  /** Código HTTP que devolvió el motor. */
  public int getStatus() {
    return status;
  }

  /** Cuerpo de la respuesta tal como llegó; Camunda suele enviar {@code {"type":...,"message":...}}. */
  public String getCuerpo() {
    return cuerpo;
  }
}
