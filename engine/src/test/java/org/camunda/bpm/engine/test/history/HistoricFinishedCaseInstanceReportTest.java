/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.camunda.bpm.engine.test.history;

import static org.junit.Assert.assertEquals;

import java.util.Date;
import java.util.List;

import org.apache.commons.lang.time.DateUtils;
import org.camunda.bpm.engine.CaseService;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.history.HistoricCaseInstance;
import org.camunda.bpm.engine.history.HistoricFinishedCaseInstanceReportResult;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.repository.CaseDefinition;
import org.camunda.bpm.engine.runtime.CaseInstance;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.engine.test.RequiredHistoryLevel;
import org.camunda.bpm.engine.test.util.ProcessEngineTestRule;
import org.camunda.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class HistoricFinishedCaseInstanceReportTest {
  public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  public ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(testRule).around(engineRule);

  protected ProcessEngineConfiguration processEngineConfiguration;
  protected HistoryService historyService;
  protected RepositoryService repositoryService;
  protected RuntimeService runtimeService;
  protected CaseService caseService;
  protected TaskService taskService;

  protected static final String CASE_DEFINITION_KEY = "one";

  @Before
  public void setUp() {
    historyService = engineRule.getHistoryService();
    processEngineConfiguration = engineRule.getProcessEngineConfiguration();
    repositoryService = engineRule.getRepositoryService();
    runtimeService = engineRule.getRuntimeService();
    caseService = engineRule.getCaseService();
    taskService = engineRule.getTaskService();

    testRule.deploy("org/camunda/bpm/engine/test/repository/one.cmmn");
  }

  @After
  public void cleanUp() {
    List<HistoricCaseInstance> instanceList = historyService.createHistoricCaseInstanceQuery().active().list();
    if (!instanceList.isEmpty()) {
      for (HistoricCaseInstance instance : instanceList) {

        caseService.terminateCaseExecution(instance.getId());
        caseService.closeCaseInstance(instance.getId());
      }
    }
    List<HistoricCaseInstance> historicCaseInstances = historyService.createHistoricCaseInstanceQuery().list();
    for (HistoricCaseInstance historicCaseInstance : historicCaseInstances) {
      historyService.deleteHistoricCaseInstance(historicCaseInstance.getId());
    }
  }

  private void prepareCaseInstances(String key, int daysInThePast, Integer historyTimeToLive, int instanceCount) {
    // update time to live
    List<CaseDefinition> caseDefinitions = repositoryService.createCaseDefinitionQuery().caseDefinitionKey(key).list();
    assertEquals(1, caseDefinitions.size());
    repositoryService.updateCaseDefinitionHistoryTimeToLive(caseDefinitions.get(0).getId(), historyTimeToLive);

    Date oldCurrentTime = ClockUtil.getCurrentTime();
    ClockUtil.setCurrentTime(DateUtils.addDays(new Date(), daysInThePast));

    for (int i = 0; i < instanceCount; i++) {
      CaseInstance caseInstance = caseService.createCaseInstanceByKey(key);
      caseService.terminateCaseExecution(caseInstance.getId());
      caseService.closeCaseInstance(caseInstance.getId());
    }

    ClockUtil.setCurrentTime(oldCurrentTime);
  }

  private void checkResultNumbers(HistoricFinishedCaseInstanceReportResult result, int expectedCleanable, int expectedFinished) {
    assertEquals(expectedCleanable, result.getCleanableCaseInstanceCount().longValue());
    assertEquals(expectedFinished, result.getFinishedCaseInstanceCount().longValue());
  }

  @Test
  public void testAllCleanable() {
    // given
    prepareCaseInstances(CASE_DEFINITION_KEY, -6, 5, 10);

    // when
    List<HistoricFinishedCaseInstanceReportResult> reportResults = historyService.createHistoricFinishedCaseInstanceReport().count();

    // then
    assertEquals(1, reportResults.size());
    checkResultNumbers(reportResults.get(0), 10, 10);
  }

  @Test
  public void testPartCleanable() {
    // given
    prepareCaseInstances(CASE_DEFINITION_KEY, -6, 5, 5);
    prepareCaseInstances(CASE_DEFINITION_KEY, 0, 5, 5);

    // when
    List<HistoricFinishedCaseInstanceReportResult> reportResults = historyService.createHistoricFinishedCaseInstanceReport().count();

    // then
    assertEquals(1, reportResults.size());
    checkResultNumbers(reportResults.get(0), 5, 10);
  }

  @Test
  public void testZeroTTL() {
    // given
    prepareCaseInstances(CASE_DEFINITION_KEY, -6, 0, 5);
    prepareCaseInstances(CASE_DEFINITION_KEY, 0, 0, 5);

    // when
    List<HistoricFinishedCaseInstanceReportResult> reportResults = historyService.createHistoricFinishedCaseInstanceReport().count();

    // then
    assertEquals(1, reportResults.size());
    checkResultNumbers(reportResults.get(0), 10, 10);
  }

  @Test
  public void testNullTTL() {
    // given
    prepareCaseInstances(CASE_DEFINITION_KEY, -6, null, 5);
    prepareCaseInstances(CASE_DEFINITION_KEY, 0, null, 5);

    // when
    List<HistoricFinishedCaseInstanceReportResult> reportResults = historyService.createHistoricFinishedCaseInstanceReport().count();

    // then
    assertEquals(1, reportResults.size());
    checkResultNumbers(reportResults.get(0), 0, 10);
  }

  @Test
  public void testComplex() {
    // given
    testRule.deploy("org/camunda/bpm/engine/test/api/cmmn/oneCaseTaskCase.cmmn", "org/camunda/bpm/engine/test/api/cmmn/oneTaskCase.cmmn",
        "org/camunda/bpm/engine/test/api/cmmn/oneTaskCaseWithHistoryTimeToLive.cmmn");
    prepareCaseInstances(CASE_DEFINITION_KEY, 0, 5, 10);
    prepareCaseInstances(CASE_DEFINITION_KEY, -6, 5, 10);
    prepareCaseInstances("oneCaseTaskCase", -6, null, 10);
    prepareCaseInstances("oneTaskCase", -6, 5, 10);
    // TODO
    // prepareCaseInstances(FOURTH_CASE_DEFINITION_KEY, -6, 0, 10);

    // repositoryService.deleteProcessDefinition(
    // repositoryService.createProcessDefinitionQuery().processDefinitionKey(SECOND_DECISION_DEFINITION_KEY).singleResult().getId(), false);

    // when
    List<HistoricFinishedCaseInstanceReportResult> reportResults = historyService.createHistoricFinishedCaseInstanceReport().count();

    // then
    assertEquals(4, reportResults.size());
    for (HistoricFinishedCaseInstanceReportResult result : reportResults) {
      if (result.getCaseDefinitionKey().equals(CASE_DEFINITION_KEY)) {
        checkResultNumbers(result, 10, 20);
      } else if (result.getCaseDefinitionKey().equals("oneCaseTaskCase")) {
        checkResultNumbers(result, 0, 10);
      } else if (result.getCaseDefinitionKey().equals("oneTaskCase")) {
        checkResultNumbers(result, 10, 10);
      } else if (result.getCaseDefinitionKey().equals("case")) {
        checkResultNumbers(result, 0, 0);
      }
    }

  }
}
