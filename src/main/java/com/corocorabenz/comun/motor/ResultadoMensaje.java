package com.corocorabenz.comun.motor;

/** Qué pasó al enviar un mensaje BPMN al motor. */
public enum ResultadoMensaje {

  /** El motor lo entregó a un proceso. */
  ENTREGADO,

  /** Ningún proceso espera ese mensaje con esa businessKey (todavía, o ya no). */
  NO_ESPERADO,

  /** No hubo conexión con el motor; se puede reintentar más tarde. */
  MOTOR_NO_DISPONIBLE
}
