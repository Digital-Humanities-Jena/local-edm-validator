package eu.europeana.metis.sandbox.dto.validation;

import eu.europeana.patternanalysis.view.ProblemPattern;
import java.util.List;

public record ValidationWorkflowReport(List<ValidationResult> validationResults,
                                       List<ProblemPattern> problemPatternList,
                                       List<ValidationStepTiming> stepTimings,
                                       long totalDurationMillis) {

  public ValidationWorkflowReport(List<ValidationResult> validationResults,
      List<ProblemPattern> problemPatternList) {
    this(validationResults, problemPatternList, List.of(), 0);
  }

}
