package org.openmetadata.service.governance.workflows.elements.nodes.userTask.impl;

import static org.openmetadata.service.governance.workflows.Workflow.EXCEPTION_VARIABLE;
import static org.openmetadata.service.governance.workflows.Workflow.RELATED_ENTITY_VARIABLE;
import static org.openmetadata.service.governance.workflows.Workflow.STAGE_INSTANCE_STATE_ID_VARIABLE;
import static org.openmetadata.service.governance.workflows.Workflow.WORKFLOW_RUNTIME_EXCEPTION;
import static org.openmetadata.service.governance.workflows.WorkflowHandler.getProcessDefinitionKeyFromId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.service.delegate.DelegateTask;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.entity.feed.Thread;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.TaskDetails;
import org.openmetadata.schema.type.TaskStatus;
import org.openmetadata.schema.type.TaskType;
import org.openmetadata.schema.type.ThreadType;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.governance.workflows.WorkflowHandler;
import org.openmetadata.service.jdbi3.FeedRepository;
import org.openmetadata.service.jdbi3.WorkflowInstanceStateRepository;
import org.openmetadata.service.resources.feeds.FeedResource;
import org.openmetadata.service.resources.feeds.MessageParser;
import org.openmetadata.service.util.WebsocketNotificationHandler;

@Slf4j
public class CreateApprovalTaskImpl implements TaskListener {
  @Override
  public void notify(DelegateTask delegateTask) {
    try {
      List<EntityReference> assignees = getAssignees(delegateTask);
      MessageParser.EntityLink entityLink =
          MessageParser.EntityLink.parse(
              (String) delegateTask.getVariable(RELATED_ENTITY_VARIABLE));
      GlossaryTerm entity = Entity.getEntity(entityLink, "*", Include.ALL);

      Thread task = createApprovalTask(entity, assignees);
      WorkflowHandler.getInstance().setCustomTaskId(delegateTask.getId(), task.getId());

      UUID workflowInstanceStateId =
          (UUID) delegateTask.getVariable(STAGE_INSTANCE_STATE_ID_VARIABLE);
      WorkflowInstanceStateRepository workflowInstanceStateRepository =
          (WorkflowInstanceStateRepository)
              Entity.getEntityTimeSeriesRepository(Entity.WORKFLOW_INSTANCE_STATE);
      workflowInstanceStateRepository.updateStageWithTask(task.getId(), workflowInstanceStateId);
    } catch (Exception exc) {
      LOG.error(
          String.format(
              "[%s] Failure: ",
              getProcessDefinitionKeyFromId(delegateTask.getProcessDefinitionId())),
          exc);
      delegateTask.setVariable(EXCEPTION_VARIABLE, exc.toString());
      throw new BpmnError(WORKFLOW_RUNTIME_EXCEPTION, exc.getMessage());
    }
  }

  private List<EntityReference> getAssignees(DelegateTask delegateTask) {
    List<EntityReference> assignees = new ArrayList<>();

    Set<IdentityLink> candidates = delegateTask.getCandidates();
    if (!candidates.isEmpty()) {
      for (IdentityLink candidate : candidates) {
        assignees.add(getEntityReferenceFromLinkString(candidate.getUserId()));
      }
    } else {
      assignees.add(getEntityReferenceFromLinkString(delegateTask.getAssignee()));
    }
    return assignees;
  }

  private EntityReference getEntityReferenceFromLinkString(String entityLinkString) {
    MessageParser.EntityLink assigneeEntityLink = MessageParser.EntityLink.parse(entityLinkString);
    return Entity.getEntityReferenceByName(
        assigneeEntityLink.getEntityType(), assigneeEntityLink.getEntityFQN(), Include.NON_DELETED);
  }

  private Thread createApprovalTask(GlossaryTerm entity, List<EntityReference> assignees) {
    FeedRepository feedRepository = Entity.getFeedRepository();
    MessageParser.EntityLink about =
        new MessageParser.EntityLink(
            Entity.getEntityTypeFromObject(entity), entity.getFullyQualifiedName());

    Thread thread;

    try {
      thread = feedRepository.getTask(about, TaskType.RequestApproval, TaskStatus.Open);
      // If there's a Task already opened, we resolve the Flowable task before creating a new
      // UserTask in the new WorkflowInstance
      WorkflowHandler.getInstance()
          .terminateTaskProcessInstance(thread.getId(), "A Newer Process Instance is Running.");
    } catch (EntityNotFoundException ex) {
      TaskDetails taskDetails =
          new TaskDetails()
              .withAssignees(FeedResource.formatAssignees(assignees))
              .withType(TaskType.RequestApproval)
              .withStatus(TaskStatus.Open);

      thread =
          new Thread()
              .withId(UUID.randomUUID())
              .withThreadTs(System.currentTimeMillis())
              .withMessage("Approval required for ")
              .withCreatedBy(entity.getUpdatedBy())
              .withAbout(about.getLinkString())
              .withType(ThreadType.Task)
              .withTask(taskDetails)
              .withUpdatedBy(entity.getUpdatedBy())
              .withUpdatedAt(System.currentTimeMillis());
      feedRepository.create(thread);

      // Send WebSocket Notification
      WebsocketNotificationHandler.handleTaskNotification(thread);
    }
    return thread;
  }
}