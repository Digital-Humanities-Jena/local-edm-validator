package eu.europeana.metis.sandbox.controller;

import static eu.europeana.metis.sandbox.common.locale.Country.GREECE;
import static eu.europeana.metis.sandbox.common.locale.Language.EL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.europeana.metis.sandbox.dto.validation.BatchValidationWorkflowReport;
import eu.europeana.metis.sandbox.dto.validation.ValidationWorkflowReport;
import eu.europeana.metis.sandbox.service.dataset.DatasetExecutionService;
import eu.europeana.metis.sandbox.service.dataset.DatasetReportService;
import eu.europeana.metis.sandbox.service.problempatterns.ExecutionPointService;
import eu.europeana.metis.sandbox.service.workflow.DirectRecordValidationService;
import eu.europeana.metis.sandbox.entity.problempatterns.ExecutionPoint;
import eu.europeana.metis.sandbox.common.batch.FullBatchJobType;
import eu.europeana.patternanalysis.PatternAnalysisService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class ValidationControllerTest {

  @Mock
  private DatasetExecutionService datasetExecutionService;
  @Mock
  private DatasetReportService datasetReportService;
  @Mock
  private PatternAnalysisService<FullBatchJobType, ExecutionPoint> patternAnalysisService;
  @Mock
  private ExecutionPointService executionPointService;
  @Mock
  private DirectRecordValidationService directRecordValidationService;

  @Test
  void validate_shouldUseDirectValidatorWithContinueOnError() throws Exception {
    ValidationWorkflowReport report = new ValidationWorkflowReport(List.of(), List.of());
    when(directRecordValidationService.validate("<rdf/>", GREECE, EL, true)).thenReturn(report);

    ValidationController controller = new ValidationController(datasetExecutionService, datasetReportService,
        patternAnalysisService, executionPointService, directRecordValidationService, true);

    ValidationWorkflowReport result = controller.validate(GREECE, EL, true,
        new MockMultipartFile("recordToValidate", "record.xml", "application/xml", "<rdf/>".getBytes()));

    assertSame(report, result);
  }

  @Test
  void validateBatch_shouldReturnReportsForEachInputFile() throws Exception {
    ValidationWorkflowReport firstReport = new ValidationWorkflowReport(List.of(), List.of());
    ValidationWorkflowReport secondReport = new ValidationWorkflowReport(List.of(), List.of());
    when(directRecordValidationService.validate("<rdf>1</rdf>", GREECE, EL, true)).thenReturn(firstReport);
    when(directRecordValidationService.validate("<rdf>2</rdf>", GREECE, EL, true)).thenReturn(secondReport);

    ValidationController controller = new ValidationController(datasetExecutionService, datasetReportService,
        patternAnalysisService, executionPointService, directRecordValidationService, true);

    List<BatchValidationWorkflowReport> reports = controller.validateBatch(GREECE, EL, true,
        List.of(
            new MockMultipartFile("recordsToValidate", "first.xml", "application/xml", "<rdf>1</rdf>".getBytes()),
            new MockMultipartFile("recordsToValidate", "second.xml", "application/xml", "<rdf>2</rdf>".getBytes())));

    assertEquals(2, reports.size());
    assertEquals("first.xml", reports.getFirst().fileName());
    assertSame(firstReport, reports.getFirst().report());
    assertEquals("second.xml", reports.get(1).fileName());
    assertSame(secondReport, reports.get(1).report());
    verify(directRecordValidationService).validate("<rdf>1</rdf>", GREECE, EL, true);
    verify(directRecordValidationService).validate("<rdf>2</rdf>", GREECE, EL, true);
  }
}
