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
package org.camunda.bpm.engine.impl.cmd;


import java.util.Collections;
import java.util.List;

import org.camunda.bpm.engine.history.UserOperationLogEntry;
import org.camunda.bpm.engine.impl.ProcessEngineLogger;
import org.camunda.bpm.engine.impl.ProcessInstanceModificationBuilderImpl;
import org.camunda.bpm.engine.impl.cfg.CommandChecker;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionManager;
import org.camunda.bpm.engine.impl.persistence.entity.PropertyChange;

/**
 * @author Thorben Lindhauer
 *
 */
public class ModifyProcessInstanceCmd implements Command<Void> {

  private final static CommandLogger LOG = ProcessEngineLogger.CMD_LOGGER;

  protected ProcessInstanceModificationBuilderImpl builder;
  protected boolean writeOperationLog;

  public ModifyProcessInstanceCmd(ProcessInstanceModificationBuilderImpl processInstanceModificationBuilder) {
    this(processInstanceModificationBuilder, true);
  }

  public ModifyProcessInstanceCmd(ProcessInstanceModificationBuilderImpl processInstanceModificationBuilder, boolean writeOperationLog) {
    this.builder = processInstanceModificationBuilder;
    this.writeOperationLog = writeOperationLog;
  }


  @Override
  public Void execute(CommandContext commandContext) {
    String processInstanceId = builder.getProcessInstanceId();

    ExecutionManager executionManager = commandContext.getExecutionManager();
    ExecutionEntity processInstance = executionManager.findExecutionById(processInstanceId);

    ensureProcessInstanceExist(processInstanceId, processInstance);

    checkUpdateProcessInstance(processInstance, commandContext);

    processInstance.setPreserveScope(true);

    List<AbstractProcessInstanceModificationCommand> instructions = builder.getModificationOperations();

    for (int i = 0; i < instructions.size(); i++) {

      AbstractProcessInstanceModificationCommand instruction = instructions.get(i);
      LOG.debugModificationInstruction(processInstanceId, i + 1, instruction.describe());

      instruction.setSkipCustomListeners(builder.isSkipCustomListeners());
      instruction.setSkipIoMappings(builder.isSkipIoMappings());
      instruction.execute(commandContext);
    }

    processInstance = executionManager.findExecutionById(processInstanceId);

    if (!processInstance.hasChildren()) {
      if (!(processInstance.getActivity() != null && !processInstance.getId().equals(processInstance.getActivityInstanceId()))) {
        // process instance was cancelled
        checkDeleteProcessInstance(processInstance, commandContext);
        deletePropagate(processInstance,"Cancellation due to process instance modifcation", builder.isSkipCustomListeners(), builder.isSkipIoMappings());
      }
      else if (processInstance.isEnded()) {
        // process instance has ended regularly
        processInstance.propagateEnd();
      }
    }

    if (writeOperationLog) {
      commandContext.getOperationLogManager().logProcessInstanceOperation(getLogEntryOperation(),
        processInstanceId,
        null,
        null,
        Collections.singletonList(PropertyChange.EMPTY_CHANGE));
    }

    return null;
  }

  protected void ensureProcessInstanceExist(String processInstanceId, ExecutionEntity processInstance) {
    if (processInstance == null) {
      throw LOG.processInstanceDoesNotExist(processInstanceId);
    }
  }

  protected String getLogEntryOperation() {
    return UserOperationLogEntry.OPERATION_TYPE_MODIFY_PROCESS_INSTANCE;
  }

  protected void checkUpdateProcessInstance(ExecutionEntity execution, CommandContext commandContext) {
    for(CommandChecker checker : commandContext.getProcessEngineConfiguration().getCommandCheckers()) {
      checker.checkUpdateProcessInstance(execution);
    }
  }

  protected void checkDeleteProcessInstance(ExecutionEntity execution, CommandContext commandContext) {
    for(CommandChecker checker : commandContext.getProcessEngineConfiguration().getCommandCheckers()) {
      checker.checkDeleteProcessInstance(execution);
    }
  }


  protected void deletePropagate(ExecutionEntity processInstance, String deleteReason, boolean skipCustomListeners, boolean skipIoMappings) {


    ExecutionEntity topmostCancellableExecution = processInstance;
    ExecutionEntity parentScopeExecution = (ExecutionEntity) topmostCancellableExecution.getParentScopeExecution(true);

    while (parentScopeExecution != null && (parentScopeExecution.getNonEventScopeExecutions().size() <= 1)) {
        topmostCancellableExecution = parentScopeExecution;
        parentScopeExecution = (ExecutionEntity) topmostCancellableExecution.getParentScopeExecution(true);
    }
    topmostCancellableExecution.deleteCascade(deleteReason, skipCustomListeners, skipIoMappings, false);
  }
}
