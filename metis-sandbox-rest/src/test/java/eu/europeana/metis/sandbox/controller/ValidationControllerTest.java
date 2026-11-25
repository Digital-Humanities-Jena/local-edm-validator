package eu.europeana.metis.sandbox.controller;

import static eu.europeana.metis.sandbox.common.locale.Country.GREECE;
import static eu.europeana.metis.sandbox.common.locale.Language.EL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.europeana.metis.sandbox.dto.validation.BatchValidationWorkflowReport;
import eu.europeana.metis.sandbox.dto.validation.ValidationWorkflowReport;
import eu.europeana.metis.sandbox.dto.validation.ValidationWorkflowReport.ValidatorIdentity;
import eu.europeana.metis.sandbox.service.dataset.DatasetExecutionService;
import eu.europeana.metis.sandbox.service.dataset.DatasetReportService;
import eu.europeana.metis.sandbox.service.problempatterns.ExecutionPointService;
import eu.europeana.metis.sandbox.service.workflow.DirectRecordValidationService;
import eu.europeana.metis.sandbox.entity.problempatterns.ExecutionPoint;
import eu.europeana.metis.sandbox.common.batch.FullBatchJobType;
import eu.europeana.patternanalysis.PatternAnalysisService;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class ValidationControllerTest {
  private static final ValidatorIdentity IDENTITY = new ValidatorIdentity("20260720", "15", "build-commit", "12-SNAPSHOT");

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

  private ValidationController controller() {
    StaticListableBeanFactory factory = new StaticListableBeanFactory();
    Properties build = new Properties();
    build.setProperty("version", "12-SNAPSHOT");
    build.setProperty("metis-schema-version", "15");
    factory.addBean("build", new BuildProperties(build));
    Properties git = new Properties();
    git.setProperty("commit.id", "build-commit");
    factory.addBean("git", new GitProperties(git));
    MockEnvironment environment = new MockEnvironment().withProperty("info.app.edm-schema-version", "20260720")
        .withProperty("info.app.source.ref", "configured-source");
    return new ValidationController(datasetExecutionService, datasetReportService, patternAnalysisService,
        executionPointService, directRecordValidationService, true, environment,
        factory.getBeanProvider(BuildProperties.class), factory.getBeanProvider(GitProperties.class));
  }

  @Test
  void validate_shouldUseDirectValidatorWithContinueOnError() throws Exception {
    ValidationWorkflowReport report = new ValidationWorkflowReport(List.of(), List.of());
    when(directRecordValidationService.validate("<rdf/>", GREECE, EL, true)).thenReturn(report);

    ValidationWorkflowReport result = controller().validate(GREECE, EL, true,
        new MockMultipartFile("recordToValidate", "record.xml", "application/xml", "<rdf/>".getBytes()));

    assertEquals(report.withValidatorIdentity(IDENTITY), result);
  }

  @Test
  void validateBatch_shouldReturnReportsForEachInputFile() throws Exception {
    ValidationWorkflowReport firstReport = new ValidationWorkflowReport(List.of(), List.of());
    ValidationWorkflowReport secondReport = new ValidationWorkflowReport(List.of(), List.of());
    when(directRecordValidationService.validate("<rdf>1</rdf>", GREECE, EL, true)).thenReturn(firstReport);
    when(directRecordValidationService.validate("<rdf>2</rdf>", GREECE, EL, true)).thenReturn(secondReport);

    List<BatchValidationWorkflowReport> reports = controller().validateBatch(GREECE, EL, true,
        List.of(
            new MockMultipartFile("recordsToValidate", "first.xml", "application/xml", "<rdf>1</rdf>".getBytes()),
            new MockMultipartFile("recordsToValidate", "second.xml", "application/xml", "<rdf>2</rdf>".getBytes())));

    assertEquals(2, reports.size());
    assertEquals("first.xml", reports.getFirst().fileName());
    assertEquals(firstReport.withValidatorIdentity(IDENTITY), reports.getFirst().report());
    assertEquals("second.xml", reports.get(1).fileName());
    assertEquals(secondReport.withValidatorIdentity(IDENTITY), reports.get(1).report());
    verify(directRecordValidationService).validate("<rdf>1</rdf>", GREECE, EL, true);
    verify(directRecordValidationService).validate("<rdf>2</rdf>", GREECE, EL, true);
  }
}
