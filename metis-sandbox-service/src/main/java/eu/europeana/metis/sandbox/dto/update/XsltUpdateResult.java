package eu.europeana.metis.sandbox.dto.update;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of refreshing the default XSLT transformation.
 *
 * @param sourceUrl the URL used to fetch the XSLT
 * @param status the update outcome
 * @param httpStatus the HTTP status returned by the upstream XSLT URL, when available
 * @param message a human-readable result message
 */
public record XsltUpdateResult(
    @JsonProperty("source-url") String sourceUrl,
    @JsonProperty("status") Status status,
    @JsonProperty("http-status") Integer httpStatus,
    @JsonProperty("message") String message) {

  public enum Status {
    UPDATED,
    UNCHANGED,
    FAILED
  }
}
