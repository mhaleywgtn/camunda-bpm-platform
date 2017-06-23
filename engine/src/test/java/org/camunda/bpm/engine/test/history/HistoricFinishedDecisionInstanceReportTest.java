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
import java.util.Map;

import org.apache.commons.lang.time.DateUtils;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.history.HistoricDecisionInstance;
import org.camunda.bpm.engine.history.HistoricFinishedDecisionInstanceReportResult;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.camunda.bpm.engine.repository.DecisionDefinition;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.camunda.bpm.engine.test.RequiredHistoryLevel;
import org.camunda.bpm.engine.test.util.ProcessEngineTestRule;
import org.camunda.bpm.engine.test.util.ProvidedProcessEngineRule;
import org.camunda.bpm.engine.variable.Variables;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

@RequiredHistoryLevel(ProcessEngineConfiguration.HISTORY_FULL)
public class HistoricFinishedDecisionInstanceReportTest {
  public ProcessEngineRule engineRule = new ProvidedProcessEngineRule();
  public ProcessEngineTestRule testRule = new ProcessEngineTestRule(engineRule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(testRule).around(engineRule);

  protected HistoryService historyService;
  protected RepositoryService repositoryService;

  protected static final String DECISION_DEFINITION_KEY = "one";
  protected static final String SECOND_DECISION_DEFINITION_KEY = "two";
  protected static final String THIRD_DECISION_DEFINITION_KEY = "anotherDecision";
  protected static final String FOURTH_DECISION_DEFINITION_KEY = "decision";

  @Before
  public void setUp() {
    historyService = engineRule.getHistoryService();
    repositoryService = engineRule.getRepositoryService();

    testRule.deploy("org/camunda/bpm/engine/test/repository/one.dmn");
  }

  @After
  public void cleanUp() {

    List<HistoricDecisionInstance> historicDecisionInstances = historyService.createHistoricDecisionInstanceQuery().list();
    for (HistoricDecisionInstance historicDecisionInstance : historicDecisionInstances) {
      historyService.deleteHistoricDecisionInstanceByInstanceId(historicDecisionInstance.getId());
    }
  }

  protected void prepareDecisionInstances(String key, int daysInThePast, Integer historyTimeToLive, int instanceCount) {
    List<DecisionDefinition> decisionDefinitions = repositoryService.createDecisionDefinitionQuery().decisionDefinitionKey(key).list();
    assertEquals(1, decisionDefinitions.size());
    repositoryService.updateDecisionDefinitionHistoryTimeToLive(decisionDefinitions.get(0).getId(), historyTimeToLive);

    Date oldCurrentTime = ClockUtil.getCurrentTime();
    ClockUtil.setCurrentTime(DateUtils.addDays(oldCurrentTime, daysInThePast));

    Map<String, Object> variables = Variables.createVariables().putValue("status", "silver").putValue("sum", 723);
    for (int i = 0; i < instanceCount; i++) {
      engineRule.getDecisionService().evaluateDecisionByKey(key).variables(variables).evaluate();
    }

    ClockUtil.setCurrentTime(oldCurrentTime);
  }

  @Test
  public void testComplex() {
    // given
    testRule.deploy("org/camunda/bpm/engine/test/repository/two.dmn", "org/camunda/bpm/engine/test/api/dmn/Another_Example.dmn",
        "org/camunda/bpm/engine/test/api/dmn/Example.dmn");
    prepareDecisionInstances(DECISION_DEFINITION_KEY, 0, 5, 10);
    prepareDecisionInstances(DECISION_DEFINITION_KEY, -6, 5, 10);
    prepareDecisionInstances(SECOND_DECISION_DEFINITION_KEY, -6, null, 10);
    prepareDecisionInstances(THIRD_DECISION_DEFINITION_KEY, -6, 5, 10);
    // TODO
    // prepareDecisionInstances(FOURTH_DECISION_DEFINITION_KEY, -6, 0, 10);

    // repositoryService.deleteProcessDefinition(
    // repositoryService.createProcessDefinitionQuery().processDefinitionKey(SECOND_DECISION_DEFINITION_KEY).singleResult().getId(), false);

    // when
    List<HistoricFinishedDecisionInstanceReportResult> reportResults = historyService.createHistoricFinishedDecisionInstanceReport().count();

    // then
    assertEquals(4, reportResults.size());
    for (HistoricFinishedDecisionInstanceReportResult result : reportResults) {
      if (result.getDecisionDefinitionKey().equals(DECISION_DEFINITION_KEY)) {
        checkResultNumbers(result, 10, 20);
      } else if (result.getDecisionDefinitionKey().equals(SECOND_DECISION_DEFINITION_KEY)) {
        checkResultNumbers(result, 0, 10);
      } else if (result.getDecisionDefinitionKey().equals(THIRD_DECISION_DEFINITION_KEY)) {
        checkResultNumbers(result, 10, 10);
      } else if (result.getDecisionDefinitionKey().equals(FOURTH_DECISION_DEFINITION_KEY)) {
        checkResultNumbers(result, 0, 0);
      }
    }

  }

  private void checkResultNumbers(HistoricFinishedDecisionInstanceReportResult result, int expectedCleanable, int expectedFinished) {
    assertEquals(expectedCleanable, result.getCleanableDecisionInstanceCount().longValue());
    assertEquals(expectedFinished, result.getFinishedDecisionInstanceCount().longValue());
  }

  @Test
  public void testAllCleanable() {
    // given
    prepareDecisionInstances(DECISION_DEFINITION_KEY, -6, 5, 10);

    // when
    List<HistoricFinishedDecisionInstanceReportResult> reportResults = historyService.createHistoricFinishedDecisionInstanceReport().count();

    // then
    assertEquals(1, reportResults.size());
    checkResultNumbers(reportResults.get(0), 10, 10);
  }

  @Test
  public void testPartCleanable() {
    // given
    prepareDecisionInstances(DECISION_DEFINITION_KEY, -6, 5, 5);
    prepareDecisionInstances(DECISION_DEFINITION_KEY, 0, 5, 5);

    // when
    List<HistoricFinishedDecisionInstanceReportResult> reportResults = historyService.createHistoricFinishedDecisionInstanceReport().count();

    // then
    assertEquals(1, reportResults.size());
    checkResultNumbers(reportResults.get(0), 5, 10);
  }

  @Test
  public void testZeroTTL() {
    // given
    prepareDecisionInstances(DECISION_DEFINITION_KEY, -6, 0, 5);
    prepareDecisionInstances(DECISION_DEFINITION_KEY, 0, 0, 5);

    // when
    List<HistoricFinishedDecisionInstanceReportResult> reportResults = historyService.createHistoricFinishedDecisionInstanceReport().count();

    // then
    assertEquals(1, reportResults.size());
    checkResultNumbers(reportResults.get(0), 10, 10);
  }

  @Test
  public void testNullTTL() {
    // given
    prepareDecisionInstances(DECISION_DEFINITION_KEY, -6, null, 5);
    prepareDecisionInstances(DECISION_DEFINITION_KEY, 0, null, 5);

    // when
    List<HistoricFinishedDecisionInstanceReportResult> reportResults = historyService.createHistoricFinishedDecisionInstanceReport().count();

    // then
    assertEquals(1, reportResults.size());
    checkResultNumbers(reportResults.get(0), 0, 10);
  }
}
