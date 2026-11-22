package com.weatherderivatives.pricer.api;

import static com.weatherderivatives.pricer.api.ApiTestSupport.JSON;
import static com.weatherderivatives.pricer.api.ApiTestSupport.body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * Spec 006 AC9 (health), AC10 (default data folder) and AC12 (localhost only), with the
 * application's own configuration: nothing is overridden here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PricerApplicationConfigTest {

  @Autowired MockMvc mvc;

  @Autowired PricerProperties properties;

  @Autowired Environment environment;

  // ---------------------------------------------------------------------------------------------
  // AC9: GET /health -> 200 {"status":"ok"}
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac9_health_200StatusOk() throws Exception {
    MockHttpServletResponse response = mvc.perform(get("/health")).andReturn().getResponse();
    String text = body(response);

    assertEquals(200, response.getStatus(), () -> "body was: " + text);
    assertNotNull(response.getContentType(), "no content type");
    assertTrue(
        MediaType.parseMediaType(response.getContentType())
            .isCompatibleWith(MediaType.APPLICATION_JSON),
        response::getContentType);
    JsonNode expected = JSON.readTree("{\"status\":\"ok\"}");
    assertEquals(expected, JSON.readTree(text));
  }

  // ---------------------------------------------------------------------------------------------
  // AC10: the default data folder is data/pricer (bootRun runs from the repo root)
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac10_defaultDataDir_isDataPricer_inTheApplicationContext() {
    assertEquals(Path.of("data", "pricer"), properties.dataDir());
  }

  @Test
  void ac10_defaultDataDir_isDataPricer_whenBoundWithNoProperties() {
    PricerProperties bound =
        new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("pricer", PricerProperties.class);
    assertEquals(Path.of("data", "pricer"), bound.dataDir());
  }

  @Test
  void ac10_dataDir_bindsFromPricerDataDirProperty() {
    PricerProperties bound =
        new Binder(new MapConfigurationPropertySource(Map.of("pricer.data-dir", "some/other")))
            .bindOrCreate("pricer", PricerProperties.class);
    assertEquals(Path.of("some", "other"), bound.dataDir());
  }

  // ---------------------------------------------------------------------------------------------
  // AC12: the server binds to 127.0.0.1 only
  // ---------------------------------------------------------------------------------------------

  @Test
  void ac12_serverAddress_is127_0_0_1() throws Exception {
    assertEquals("127.0.0.1", environment.getProperty("server.address"));
    ServerProperties server =
        Binder.get(environment).bind("server", ServerProperties.class).orElse(null);
    assertNotNull(server, "no server.* properties bound");
    assertEquals(InetAddress.getByName("127.0.0.1"), server.getAddress());
  }
}
