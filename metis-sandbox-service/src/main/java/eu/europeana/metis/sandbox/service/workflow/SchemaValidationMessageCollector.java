package eu.europeana.metis.sandbox.service.workflow;

import eu.europeana.metis.sandbox.dto.validation.RecordValidationMessage;
import eu.europeana.metis.sandbox.dto.validation.RecordValidationMessage.Type;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

@Component
@Slf4j
public class SchemaValidationMessageCollector {

  private static final String SCHEMA_RESOURCE_PREFIX = "schema_xsds/";
  private static final int MAX_RECOVERY_PASSES = 25;
  private static final Map<String, String> ROOT_SCHEMA_BY_NAME =
      Map.of("EDM-EXTERNAL", "EDM.xsd", "EDM-INTERNAL", "EDM-INTERNAL.xsd");

  private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

  public List<RecordValidationMessage> collect(String schemaName, String recordData) {
    List<RecordValidationMessage> messages = collectOnce(schemaName, recordData);
    if (messages.stream().noneMatch(message -> message.messageType() == Type.ERROR)) {
      return messages;
    }

    List<RecordValidationMessage> collectedMessages = new ArrayList<>(messages);
    Set<String> seenMessages = new LinkedHashSet<>();
    messages.forEach(message -> seenMessages.add(message.messageType() + ":" + message.message()));

    String recordToValidate = recordData;
    for (int pass = 0; pass < MAX_RECOVERY_PASSES; pass++) {
      Optional<OffendingElement> offendingElement = findOffendingElement(messages);
      if (offendingElement.isEmpty()) {
        break;
      }

      Optional<String> recoveredRecord = removeFirstElement(recordToValidate, offendingElement.get());
      if (recoveredRecord.isEmpty()) {
        break;
      }

      recordToValidate = recoveredRecord.get();
      messages = collectOnce(schemaName, recordToValidate);
      if (messages.isEmpty()) {
        break;
      }

      for (RecordValidationMessage message : messages) {
        if (seenMessages.add(message.messageType() + ":" + message.message())) {
          collectedMessages.add(message);
        }
      }
    }
    return collectedMessages;
  }

  private List<RecordValidationMessage> collectOnce(String schemaName, String recordData) {
    List<RecordValidationMessage> messages = new ArrayList<>();
    try {
      javax.xml.validation.Validator validator = getSchema(schemaName).newValidator();
      validator.setErrorHandler(new CollectingErrorHandler(messages));
      validator.validate(new StreamSource(new StringReader(recordData)));
    } catch (SAXException e) {
      if (messages.isEmpty()) {
        messages.add(new RecordValidationMessage(Type.ERROR, getExceptionMessage(e)));
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Could not collect schema validation messages for " + schemaName, e);
    }
    return messages;
  }

  private static Optional<OffendingElement> findOffendingElement(List<RecordValidationMessage> messages) {
    return messages.stream()
                   .filter(message -> message.messageType() == Type.ERROR)
                   .map(RecordValidationMessage::message)
                   .map(SchemaValidationMessageCollector::findOffendingElement)
                   .flatMap(Optional::stream)
                   .findFirst();
  }

  private static Optional<OffendingElement> findOffendingElement(String message) {
    int elementMarkerStart = message.indexOf("element '");
    if (elementMarkerStart < 0) {
      return Optional.empty();
    }
    int elementStart = elementMarkerStart + "element '".length();
    int elementEnd = message.indexOf('\'', elementStart);
    if (elementEnd < 0) {
      return Optional.empty();
    }

    String elementName = message.substring(elementStart, elementEnd);
    if (elementName.startsWith("{\"")) {
      int namespaceEnd = elementName.indexOf("\":");
      int localNameEnd = elementName.lastIndexOf('}');
      if (namespaceEnd > 2 && localNameEnd > namespaceEnd) {
        return Optional.of(new OffendingElement(elementName.substring(2, namespaceEnd),
            elementName.substring(namespaceEnd + 2, localNameEnd)));
      }
    }

    int prefixEnd = elementName.indexOf(':');
    if (prefixEnd > 0) {
      return Optional.of(new OffendingElement(elementName.substring(0, prefixEnd), elementName.substring(prefixEnd + 1)));
    }
    return Optional.of(new OffendingElement(null, elementName));
  }

  private static Optional<String> removeFirstElement(String recordData, OffendingElement offendingElement) {
    try {
      DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
      documentBuilderFactory.setNamespaceAware(true);
      documentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      Document document = documentBuilderFactory.newDocumentBuilder().parse(new InputSource(new StringReader(recordData)));

      Node nodeToRemove = findNodeToRemove(document, offendingElement);
      if (nodeToRemove == null || nodeToRemove.getParentNode() == null) {
        return Optional.empty();
      }
      nodeToRemove.getParentNode().removeChild(nodeToRemove);

      TransformerFactory transformerFactory = TransformerFactory.newInstance();
      transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      javax.xml.transform.Transformer transformer = transformerFactory.newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      StringWriter writer = new StringWriter();
      transformer.transform(new DOMSource(document), new StreamResult(writer));
      return Optional.of(writer.toString());
    } catch (Exception e) {
      log.debug("Could not recover after schema validation error {}", offendingElement, e);
      return Optional.empty();
    }
  }

  private static Node findNodeToRemove(Document document, OffendingElement offendingElement) {
    String namespaceUri = offendingElement.namespaceOrPrefix();
    if (namespaceUri != null && !namespaceUri.startsWith("http")) {
      namespaceUri = document.getDocumentElement().lookupNamespaceURI(namespaceUri);
    }

    if (namespaceUri != null) {
      org.w3c.dom.NodeList nodes = document.getElementsByTagNameNS(namespaceUri, offendingElement.localName());
      if (nodes.getLength() > 0) {
        return nodes.item(0);
      }
    }

    org.w3c.dom.NodeList elements = document.getElementsByTagName("*");
    for (int i = 0; i < elements.getLength(); i++) {
      Element element = (Element) elements.item(i);
      if (offendingElement.localName().equals(element.getLocalName())) {
        return element;
      }
    }
    return null;
  }

  private Schema getSchema(String schemaName) {
    return schemas.computeIfAbsent(schemaName, this::createSchema);
  }

  private Schema createSchema(String schemaName) {
    String rootSchema = ROOT_SCHEMA_BY_NAME.get(schemaName);
    if (rootSchema == null) {
      throw new IllegalArgumentException("Unsupported predefined schema: " + schemaName);
    }

    SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    schemaFactory.setResourceResolver(new ClasspathSchemaResourceResolver());
    try (InputStream schemaStream = getSchemaResource(rootSchema)) {
      Source source = new StreamSource(schemaStream);
      source.setSystemId(rootSchema);
      return schemaFactory.newSchema(source);
    } catch (Exception e) {
      throw new IllegalStateException("Could not load schema " + schemaName, e);
    }
  }

  private static InputStream getSchemaResource(String systemId) {
    InputStream stream = SchemaValidationMessageCollector.class.getClassLoader()
                                                              .getResourceAsStream(SCHEMA_RESOURCE_PREFIX + systemId);
    if (stream == null) {
      throw new IllegalStateException("Schema resource not found: " + SCHEMA_RESOURCE_PREFIX + systemId);
    }
    return stream;
  }

  private static String getExceptionMessage(SAXException exception) {
    return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
  }

  private static String formatMessage(Type type, SAXParseException exception) {
    StringBuilder builder = new StringBuilder();
    builder.append(exception.getMessage());
    if (exception.getLineNumber() > 0) {
      builder.append(" [line ").append(exception.getLineNumber());
      if (exception.getColumnNumber() > 0) {
        builder.append(", column ").append(exception.getColumnNumber());
      }
      builder.append(']');
    }
    if (type == Type.WARN) {
      builder.insert(0, "Warning: ");
    }
    return builder.toString();
  }

  private static class CollectingErrorHandler implements ErrorHandler {

    private final List<RecordValidationMessage> messages;

    private CollectingErrorHandler(List<RecordValidationMessage> messages) {
      this.messages = messages;
    }

    @Override
    public void warning(SAXParseException exception) {
      messages.add(new RecordValidationMessage(Type.WARN, formatMessage(Type.WARN, exception)));
    }

    @Override
    public void error(SAXParseException exception) {
      messages.add(new RecordValidationMessage(Type.ERROR, formatMessage(Type.ERROR, exception)));
    }

    @Override
    public void fatalError(SAXParseException exception) {
      messages.add(new RecordValidationMessage(Type.ERROR, formatMessage(Type.ERROR, exception)));
    }
  }

  private static class ClasspathSchemaResourceResolver implements LSResourceResolver {

    @Override
    public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
      try {
        InputStream stream = getSchemaResource(systemId);
        return new SchemaResourceInput(publicId, systemId, baseURI, stream);
      } catch (RuntimeException e) {
        log.error("Could not resolve schema resource {}", systemId, e);
        return null;
      }
    }
  }

  private record OffendingElement(String namespaceOrPrefix, String localName) {

  }

  private record SchemaResourceInput(String publicId, String systemId, String baseURI,
                                     InputStream byteStream) implements LSInput {

    @Override
    public Reader getCharacterStream() {
      return null;
    }

    @Override
    public void setCharacterStream(Reader characterStream) {
      throw new UnsupportedOperationException();
    }

    @Override
    public InputStream getByteStream() {
      return byteStream;
    }

    @Override
    public void setByteStream(InputStream byteStream) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getStringData() {
      return null;
    }

    @Override
    public void setStringData(String stringData) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getSystemId() {
      return systemId;
    }

    @Override
    public void setSystemId(String systemId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getPublicId() {
      return publicId;
    }

    @Override
    public void setPublicId(String publicId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getBaseURI() {
      return baseURI;
    }

    @Override
    public void setBaseURI(String baseURI) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getEncoding() {
      return null;
    }

    @Override
    public void setEncoding(String encoding) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean getCertifiedText() {
      return false;
    }

    @Override
    public void setCertifiedText(boolean certifiedText) {
      throw new UnsupportedOperationException();
    }
  }
}
