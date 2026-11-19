package com.corocorabenz.comun.motor;

/** No hubo conexión con el motor o no respondió a tiempo. */
public class MotorNoDisponibleException extends RuntimeException {

  public MotorNoDisponibleException(String mensaje, Throwable causa) {
    super(mensaje, causa);
  }
}
