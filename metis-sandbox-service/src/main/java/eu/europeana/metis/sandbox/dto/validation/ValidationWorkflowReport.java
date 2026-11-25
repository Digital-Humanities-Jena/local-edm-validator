package eu.europeana.metis.sandbox.dto.validation;

import eu.europeana.patternanalysis.view.ProblemPattern;
import java.util.List;

public record ValidationWorkflowReport(List<ValidationResult> validationResults,
                                       List<ProblemPattern> problemPatternList,
                                       List<ValidationStepTiming> stepTimings,
                                       long totalDurationMillis,
                                       ValidatorIdentity validatorIdentity) {

  public record ValidatorIdentity(String edmSchemaVersion, String metisSchemaVersion, String sourceRef,
                                  String buildVersion) {}

  public ValidationWorkflowReport(List<ValidationResult> validationResults, List<ProblemPattern> problemPatternList,
      List<ValidationStepTiming> stepTimings, long totalDurationMillis) {
    this(validationResults, problemPatternList, stepTimings, totalDurationMillis, null);
  }

  public ValidationWorkflowReport(List<ValidationResult> validationResults,
      List<ProblemPattern> problemPatternList) {
    this(validationResults, problemPatternList, List.of(), 0);
  }

  public ValidationWorkflowReport withValidatorIdentity(ValidatorIdentity identity) {
    return new ValidationWorkflowReport(validationResults, problemPatternList, stepTimings, totalDurationMillis, identity);
  }

}
