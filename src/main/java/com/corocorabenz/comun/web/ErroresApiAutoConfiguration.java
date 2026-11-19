package com.corocorabenz.comun.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/** Activa {@link ErroresApi} en cualquier aplicación web que use cb-comun. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ErroresApiAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ErroresApi erroresApi() {
    return new ErroresApi();
  }
}
