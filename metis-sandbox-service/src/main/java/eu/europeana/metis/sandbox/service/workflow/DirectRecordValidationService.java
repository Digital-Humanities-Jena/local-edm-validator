package eu.europeana.metis.sandbox.service.workflow;

import eu.europeana.metis.sandbox.common.batch.FullBatchJobType;
import eu.europeana.metis.sandbox.common.batch.TransformationBatchJobSubType;
import eu.europeana.metis.sandbox.common.locale.Country;
import eu.europeana.metis.sandbox.common.locale.Language;
import eu.europeana.metis.sandbox.dto.validation.RecordValidationMessage;
import eu.europeana.metis.sandbox.dto.validation.RecordValidationMessage.Type;
import eu.europeana.metis.sandbox.dto.validation.ValidationResult;
import eu.europeana.metis.sandbox.dto.validation.ValidationResult.Status;
import eu.europeana.metis.sandbox.dto.validation.ValidationStepTiming;
import eu.europeana.metis.sandbox.dto.validation.ValidationWorkflowReport;
import eu.europeana.metis.sandbox.entity.TransformXsltEntity;
import eu.europeana.metis.sandbox.entity.XsltType;
import eu.europeana.metis.sandbox.entity.problempatterns.ExecutionPoint;
import eu.europeana.metis.sandbox.repository.TransformXsltRepository;
import eu.europeana.metis.schema.convert.RdfConversionUtils;
import eu.europeana.metis.schema.convert.SerializationException;
import eu.europeana.metis.transformation.service.TransformationException;
import eu.europeana.metis.transformation.service.XsltTransformer;
import eu.europeana.patternanalysis.PatternAnalysisService;
import eu.europeana.patternanalysis.view.ProblemPattern;
import eu.europeana.validation.service.ValidationExecutionService;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
@Slf4j
public class DirectRecordValidationService {

  private static final String SUCCESS_MESSAGE = "success";
  private static final String DIRECT_VALIDATION_DATASET_ID = "0";
  private static final String DIRECT_VALIDATION_DATASET_NAME = "direct_validation";

  private final ValidationExecutionService validationExecutionService;
  private final ObjectFactory<XsltTransformer> xsltTransformerFactory;
  private final TransformXsltRepository transformXsltRepository;
  private final TransformService transformService;
  private final PatternAnalysisService<FullBatchJobType, ExecutionPoint> patternAnalysisService;
  private final SchemaValidationMessageCollector schemaValidationMessageCollector;
  private final RdfConversionUtils rdfConversionUtils = new RdfConversionUtils();

  public ValidationWorkflowReport validate(String recordData, Country country, Language language) {
    return validate(recordData, country, language, false);
  }

  public ValidationWorkflowReport validate(String recordData, Country country, Language language, boolean continueOnError) {
    long totalStart = System.nanoTime();
    List<ValidationResult> validationResults = new ArrayList<>();
    List<ValidationStepTiming> stepTimings = new ArrayList<>();
    long stepStart = System.nanoTime();
    validationResults.add(passed(FullBatchJobType.HARVEST_FILE));
    stepTimings.add(timing(FullBatchJobType.HARVEST_FILE.name(), stepStart));

    String reorderedRecordData;
    boolean externalValidationPassed;
    stepStart = System.nanoTime();
    try {
      reorderedRecordData = reorderFileContent(recordData);
      externalValidationPassed = addValidationResult(validationResults, FullBatchJobType.VALIDATE_EXTERNAL, "EDM-EXTERNAL",
          reorderedRecordData);
    } catch (TransformationException e) {
      validationResults.add(failed(FullBatchJobType.VALIDATE_EXTERNAL, e));
      externalValidationPassed = false;
    } finally {
      stepTimings.add(timing(FullBatchJobType.VALIDATE_EXTERNAL.name(), stepStart));
    }
    if (!externalValidationPassed && !continueOnError) {
      return report(validationResults, List.of(), stepTimings, totalStart);
    }

    String transformedRecordData;
    stepStart = System.nanoTime();
    try {
      transformedRecordData = transformInternal(recordData, country, language);
      validationResults.add(passed(FullBatchJobType.TRANSFORM_INTERNAL));
    } catch (TransformationException | RuntimeException e) {
      validationResults.add(failed(FullBatchJobType.TRANSFORM_INTERNAL, e));
      stepTimings.add(timing(FullBatchJobType.TRANSFORM_INTERNAL.name(), stepStart));
      return report(validationResults, List.of(), stepTimings, totalStart);
    }
    stepTimings.add(timing(FullBatchJobType.TRANSFORM_INTERNAL.name(), stepStart));

    stepStart = System.nanoTime();
    boolean internalValidationPassed =
        addValidationResult(validationResults, FullBatchJobType.VALIDATE_INTERNAL, "EDM-INTERNAL", transformedRecordData);
    stepTimings.add(timing(FullBatchJobType.VALIDATE_INTERNAL.name(), stepStart));
    if (!internalValidationPassed && !continueOnError) {
      return report(validationResults, List.of(), stepTimings, totalStart);
    }

    stepStart = System.nanoTime();
    List<ProblemPattern> problemPatterns = getProblemPatterns(transformedRecordData);
    stepTimings.add(timing("PROBLEM_PATTERNS", stepStart));
    return report(validationResults, problemPatterns, stepTimings, totalStart);
  }

  private boolean addValidationResult(List<ValidationResult> validationResults, FullBatchJobType step, String schema,
      String recordData) {
    List<RecordValidationMessage> schemaMessages = schemaValidationMessageCollector.collect(schema, recordData);
    if (!schemaMessages.isEmpty()) {
      boolean hasErrors = schemaMessages.stream().anyMatch(message -> message.messageType() == Type.ERROR);
      if (hasErrors) {
        validationResults.add(new ValidationResult(step, schemaMessages, Status.FAILED));
        return false;
      }
    }

    final eu.europeana.validation.model.ValidationResult result;
    try {
      result = validationExecutionService.singleValidation(schema, null, null, recordData);
    } catch (RuntimeException e) {
      validationResults.add(failed(step, e));
      return false;
    }

    if (result.isSuccess()) {
      validationResults.add(schemaMessages.isEmpty() ? passed(step) : new ValidationResult(step, schemaMessages, Status.PASSED));
      return true;
    }
    validationResults.add(new ValidationResult(step, List.of(new RecordValidationMessage(Type.ERROR, result.getMessage())),
        Status.FAILED));
    return false;
  }

  private String reorderFileContent(String recordData) throws TransformationException {
    try (XsltTransformer xsltTransformer = xsltTransformerFactory.getObject();
        StringWriter writer = xsltTransformer.transform(recordData.getBytes(StandardCharsets.UTF_8), null)) {
      return writer.toString();
    } catch (IOException e) {
      throw new TransformationException(e);
    }
  }

  private String transformInternal(String recordData, Country country, Language language) throws TransformationException {
    String xsltId = transformXsltRepository.findFirstByTypeOrderById(XsltType.DEFAULT)
                                           .map(TransformXsltEntity::getId)
                                           .map(String::valueOf)
                                           .orElseThrow();
    return transformService.transformRecord(
        DIRECT_VALIDATION_DATASET_NAME,
        recordData,
        xsltId,
        TransformationBatchJobSubType.INTERNAL,
        DIRECT_VALIDATION_DATASET_ID,
        DIRECT_VALIDATION_DATASET_NAME,
        country.xmlValue(),
        language.name().toLowerCase(Locale.US));
  }

  private List<ProblemPattern> getProblemPatterns(String transformedRecordData) {
    try {
      return patternAnalysisService.getRecordPatternAnalysis(rdfConversionUtils.convertStringToRdf(transformedRecordData));
    } catch (SerializationException e) {
      log.error("Problem pattern analysis failed during direct validation", e);
      return List.of();
    }
  }

  private static ValidationResult passed(FullBatchJobType step) {
    return new ValidationResult(step, List.of(new RecordValidationMessage(Type.INFO, SUCCESS_MESSAGE)), Status.PASSED);
  }

  private static ValidationResult failed(FullBatchJobType step, Exception exception) {
    String message = exception.getMessage();
    if (message == null && exception.getCause() != null) {
      message = exception.getCause().getMessage();
    }
    if (message == null) {
      message = exception.getClass().getSimpleName();
    }
    return new ValidationResult(step, List.of(new RecordValidationMessage(Type.ERROR, message)), Status.FAILED);
  }

  private static ValidationStepTiming timing(String step, long startNanos) {
    return new ValidationStepTiming(step, (System.nanoTime() - startNanos) / 1_000_000);
  }

  private static ValidationWorkflowReport report(List<ValidationResult> validationResults,
      List<ProblemPattern> problemPatterns, List<ValidationStepTiming> stepTimings, long totalStartNanos) {
    return new ValidationWorkflowReport(validationResults, problemPatterns, stepTimings,
        (System.nanoTime() - totalStartNanos) / 1_000_000);
  }
}
