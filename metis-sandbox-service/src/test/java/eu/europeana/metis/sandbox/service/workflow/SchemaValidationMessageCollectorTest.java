package eu.europeana.metis.sandbox.service.workflow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.europeana.metis.sandbox.dto.validation.RecordValidationMessage;
import eu.europeana.metis.sandbox.dto.validation.RecordValidationMessage.Type;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaValidationMessageCollectorTest {

  private final SchemaValidationMessageCollector collector = new SchemaValidationMessageCollector();

  @Test
  void collect_shouldReturnAllXsdErrors() {
    String record = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF
            xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            xmlns:edm="http://www.europeana.eu/schemas/edm/"
            xmlns:schema="http://schema.org/">
          <edm:WebResource rdf:about="http://example.org/one">
            <schema:digitalSourceType rdf:resource="http://example.org/source"/>
          </edm:WebResource>
          <edm:WebResource rdf:about="http://example.org/two">
            <edm:intendedUsage>thumbnail</edm:intendedUsage>
          </edm:WebResource>
        </rdf:RDF>
        """;

    List<RecordValidationMessage> messages = collector.collect("EDM-EXTERNAL", record);

    assertTrue(messages.size() >= 2);
    assertTrue(messages.stream().filter(message -> message.messageType() == Type.ERROR).count() >= 2);
    assertTrue(messages.stream().anyMatch(message -> message.message().contains("digitalSourceType")));
    assertTrue(messages.stream().anyMatch(message -> message.message().contains("edm:intendedUsage")));
  }

  @Test
  void collect_shouldRecoverLaterErrorsInSameParent() {
    String record = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF
            xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            xmlns:dc="http://purl.org/dc/elements/1.1/"
            xmlns:edm="http://www.europeana.eu/schemas/edm/"
            xmlns:schema="http://schema.org/">
          <edm:WebResource rdf:about="http://example.org/web-resource">
            <dc:format>model/gltf-binary</dc:format>
            <edm:rights rdf:resource="http://creativecommons.org/licenses/by/4.0/"/>
            <schema:digitalSourceType rdf:resource="https://cv.iptc.org/newscodes/digitalsourcetype/dataDrivenMedia"/>
            <dc:subject>bad later element</dc:subject>
          </edm:WebResource>
        </rdf:RDF>
        """;

    List<RecordValidationMessage> messages = collector.collect("EDM-EXTERNAL", record);

    assertTrue(messages.size() >= 2, () -> messages.toString());
    assertTrue(messages.stream().anyMatch(message -> message.message().contains("digitalSourceType")),
        () -> messages.toString());
    assertTrue(messages.stream().anyMatch(message -> message.message().contains("dc:subject")
        || message.message().contains("subject")), () -> messages.toString());
  }

  @Test
  void collect_shouldReturnNoMessagesForXsdValidRecord() {
    String record = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF
            xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            xmlns:edm="http://www.europeana.eu/schemas/edm/">
          <edm:ProvidedCHO rdf:about="http://example.org/cho">
            <edm:type>IMAGE</edm:type>
          </edm:ProvidedCHO>
        </rdf:RDF>
        """;

    assertTrue(collector.collect("EDM-EXTERNAL", record).isEmpty());
  }
}
