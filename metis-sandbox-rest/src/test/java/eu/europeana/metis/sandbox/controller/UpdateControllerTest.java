package eu.europeana.metis.sandbox.controller;

import static eu.europeana.metis.sandbox.dto.update.XsltUpdateResult.Status.UPDATED;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.europeana.metis.sandbox.dto.update.XsltUpdateResult;
import eu.europeana.metis.sandbox.service.util.XsltUrlUpdateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpdateControllerTest {

  private static final String CONFIGURED_URL = "http://configured.example/xslt";
  private static final String OVERRIDE_URL = "http://override.example/xslt";

  @Mock
  private XsltUrlUpdateService xsltUrlUpdateService;

  @Test
  void updateDefaultXslt_shouldUseConfiguredUrlWhenNoOverrideIsProvided() {
    XsltUpdateResult expected = new XsltUpdateResult(CONFIGURED_URL, UPDATED, 200, "Default XSLT updated.");
    when(xsltUrlUpdateService.updateXslt(CONFIGURED_URL)).thenReturn(expected);

    UpdateController controller = new UpdateController(xsltUrlUpdateService, CONFIGURED_URL);

    XsltUpdateResult result = controller.updateDefaultXslt(null);

    assertSame(expected, result);
    verify(xsltUrlUpdateService).updateXslt(CONFIGURED_URL);
  }

  @Test
  void updateDefaultXslt_shouldUseOverrideUrlWhenProvided() {
    XsltUpdateResult expected = new XsltUpdateResult(OVERRIDE_URL, UPDATED, 200, "Default XSLT updated.");
    when(xsltUrlUpdateService.updateXslt(OVERRIDE_URL)).thenReturn(expected);

    UpdateController controller = new UpdateController(xsltUrlUpdateService, CONFIGURED_URL);

    XsltUpdateResult result = controller.updateDefaultXslt(OVERRIDE_URL);

    assertSame(expected, result);
    verify(xsltUrlUpdateService).updateXslt(OVERRIDE_URL);
  }
}
