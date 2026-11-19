package com.corocorabenz.comun;

import static org.assertj.core.api.Assertions.assertThat;

import com.corocorabenz.comun.credencial.CredencialInterna;
import com.corocorabenz.comun.credencial.CredencialInternaAutoConfiguration;
import com.corocorabenz.comun.credencial.FiltroCredencialInterna;
import com.corocorabenz.comun.motor.MotorAutoConfiguration;
import com.corocorabenz.comun.motor.MotorClient;
import com.corocorabenz.comun.portal.SeguridadPortalAutoConfiguration;
import com.corocorabenz.comun.portal.SesionController;
import com.corocorabenz.comun.web.ErroresApi;
import com.corocorabenz.comun.web.ErroresApiAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/** Qué registra cb-comun según las propiedades de cada sistema (motor, Banco, Bodega, portales). */
class AutoconfiguracionTest {

  private final WebApplicationContextRunner contexto = new WebApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          CredencialInternaAutoConfiguration.class,
          MotorAutoConfiguration.class,
          SeguridadPortalAutoConfiguration.class,
          ErroresApiAutoConfiguration.class));

  @Test
  void soloConElTokenNoHayFiltroNiClienteDelMotorNiSesion() {
    contexto.withPropertyValues("corocora.internal-token=secreto").run(aplicacion -> {
      assertThat(aplicacion).hasSingleBean(CredencialInterna.class);
      assertThat(aplicacion).hasSingleBean(ErroresApi.class);
      assertThat(aplicacion).doesNotHaveBean(FilterRegistrationBean.class);
      assertThat(aplicacion).doesNotHaveBean(MotorClient.class);
      assertThat(aplicacion).doesNotHaveBean(SesionController.class);
    });
  }

  @Test
  void sinTokenLaAplicacionNoArranca() {
    contexto.run(aplicacion -> assertThat(aplicacion)
        .hasFailed()
        .getFailure()
        .rootCause()
        .hasMessageContaining("corocora.internal-token"));
  }

  @Test
  void lasRutasInternasSeRegistranComoPatronesDelFiltro() {
    contexto.withPropertyValues(
        "corocora.internal-token=secreto",
        "corocora.rutas-internas= /engine-rest/* , /api/demo/* ,")
        .run(aplicacion -> {
          FilterRegistrationBean<?> registro = aplicacion.getBean(FilterRegistrationBean.class);
          assertThat(registro.getFilter()).isInstanceOf(FiltroCredencialInterna.class);
          assertThat(registro.getUrlPatterns()).containsExactly("/engine-rest/*", "/api/demo/*");
          assertThat(registro.isEnabled()).isTrue();
          assertThat(registro.getOrder()).isLessThan(-100);
        });
  }

  @Test
  void unaListaDeRutasVaciaNoProtegeTodaLaAplicacion() {
    contexto.withPropertyValues("corocora.internal-token=secreto", "corocora.rutas-internas= , ")
        .run(aplicacion -> {
          FilterRegistrationBean<?> registro = aplicacion.getBean(FilterRegistrationBean.class);
          assertThat(registro.isEnabled()).isFalse();
        });
  }

  @Test
  void conLaUrlDelMotorHayClienteDelMotor() {
    contexto.withPropertyValues(
        "corocora.internal-token=secreto",
        "corocora.engine-rest=http://localhost:9000/engine-rest")
        .run(aplicacion -> assertThat(aplicacion).hasSingleBean(MotorClient.class));
  }

  /** Banco, Taller-Bodega y el motor no llevan Spring Security: la librería debe cargar igual. */
  @Test
  void sinSpringSecurityEnElClasspathLaSesionDePortalSeOmite() {
    contexto.withClassLoader(new FilteredClassLoader("org.springframework.security"))
        .withPropertyValues(
            "corocora.internal-token=secreto",
            "corocora.engine-rest=http://localhost:9000/engine-rest",
            "corocora.portal.habilitado=true")
        .run(aplicacion -> {
          assertThat(aplicacion).hasNotFailed();
          assertThat(aplicacion).hasSingleBean(MotorClient.class);
          assertThat(aplicacion).doesNotHaveBean(SesionController.class);
        });
  }
}
