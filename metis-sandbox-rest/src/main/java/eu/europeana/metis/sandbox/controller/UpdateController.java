package eu.europeana.metis.sandbox.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import eu.europeana.metis.sandbox.dto.update.XsltUpdateResult;
import eu.europeana.metis.sandbox.service.util.XsltUrlUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for manually triggering runtime updates.
 */
@RestController
@RequestMapping("/update/")
@Tag(name = "Update Controller")
public class UpdateController {

  private final XsltUrlUpdateService xsltUrlUpdateService;
  private final String defaultXsltUrl;

  /**
   * Constructor.
   *
   * @param xsltUrlUpdateService service used to refresh the default XSLT
   * @param defaultXsltUrl configured default XSLT source URL
   */
  public UpdateController(XsltUrlUpdateService xsltUrlUpdateService,
      @Value("${sandbox.transformation.xslt-url}") String defaultXsltUrl) {
    this.xsltUrlUpdateService = xsltUrlUpdateService;
    this.defaultXsltUrl = defaultXsltUrl;
  }

  /**
   * Refreshes the default transformation XSLT.
   *
   * @param url optional override URL to fetch the XSLT from
   * @return the update result
   */
  @Operation(summary = "Refresh default XSLT", description = "Fetches and stores the default transformation XSLT")
  @ApiResponse(responseCode = "200", description = "Update request completed")
  @ApiResponse(responseCode = "400", description = "Invalid URL")
  @PostMapping(value = "default-xslt", produces = APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.OK)
  public XsltUpdateResult updateDefaultXslt(
      @Parameter(description = "Optional source URL. If omitted, the configured default XSLT URL is used.")
      @RequestParam(value = "url", required = false) String url) {
    String sourceUrl = url == null || url.isBlank() ? defaultXsltUrl : url;
    return xsltUrlUpdateService.updateXslt(sourceUrl);
  }
}
