package com.weatherderivatives.pricer.api;

import static com.weatherderivatives.pricer.api.ApiTestSupport.assertError;
import static com.weatherderivatives.pricer.api.ApiTestSupport.body;
import static com.weatherderivatives.pricer.api.ApiTestSupport.postPrice;
import static com.weatherderivatives.pricer.api.ApiTestSupport.workedExampleRequest;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spec 006 AC8: an unexpected exception gives 500 {@code internal_error} with no stack trace or
 * file-system details. The data source is replaced by one that throws, so no file is involved.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceEndpointInternalErrorTest {

  /** Message of the unexpected failure: it must not leak into the response. */
  static final String SECRET = "boom at C:\\secret\\path";

  @TestConfiguration
  static class FailingSourceConfig {
    @Bean
    @Primary
    TemperatureSource failingTemperatureSource() {
      return city -> {
        throw new RuntimeException(SECRET);
      };
    }
  }

  @Autowired MockMvc mvc;

  @Test
  void ac8_unexpectedException_500InternalError_noStackTraceOrFileSystemDetails() throws Exception {
    MockHttpServletResponse response = postPrice(mvc, workedExampleRequest("london"));

    String message = assertError(response, 500, "internal_error");
    String text = body(response);
    for (String leak :
        new String[] {
          "boom",
          "secret",
          "C:\\",
          "C:\\\\",
          "\\path",
          "Exception",
          "RuntimeException",
          "java.",
          "com.weatherderivatives",
          "\tat ",
          ".java:",
        }) {
      assertFalse(text.contains(leak), () -> "response leaks '" + leak + "': " + text);
    }
    assertFalse(message.toLowerCase(Locale.ROOT).contains("trace"), () -> "message: " + message);
  }
}
