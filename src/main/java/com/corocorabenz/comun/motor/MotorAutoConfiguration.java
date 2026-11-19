package com.corocorabenz.comun.motor;

import com.corocorabenz.comun.credencial.CredencialInterna;
import com.corocorabenz.comun.credencial.CredencialInternaAutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Registra {@link MotorClient} en los sistemas que declaran {@code corocora.engine-rest}. */
@AutoConfiguration(after = CredencialInternaAutoConfiguration.class)
@ConditionalOnProperty("corocora.engine-rest")
public class MotorAutoConfiguration {

  // El bean no se llama "motorClient" para no chocar con las clases MotorClient
  // propias que tenían Banco y Taller-Bodega, si alguna siguiera existiendo.
  @Bean
  @ConditionalOnMissingBean
  MotorClient clienteDelMotor(CredencialInterna credencial, @Value("${corocora.engine-rest}") String engineRest) {
    return new MotorClient(credencial, engineRest);
  }
}
