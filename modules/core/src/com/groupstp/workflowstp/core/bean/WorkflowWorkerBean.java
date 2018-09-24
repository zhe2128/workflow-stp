package com.groupstp.workflowstp.core.bean;

import com.groupstp.workflowstp.core.util.JsonUtil;
import com.groupstp.workflowstp.dto.WorkflowExecutionContext;
import com.groupstp.workflowstp.entity.*;
import com.groupstp.workflowstp.exception.WorkflowException;
import com.haulmont.bali.util.Preconditions;
import com.haulmont.chile.core.model.MetaClass;
import com.haulmont.chile.core.model.MetaProperty;
import com.haulmont.cuba.core.Persistence;
import com.haulmont.cuba.core.Transaction;
import com.haulmont.cuba.core.entity.Entity;
import com.haulmont.cuba.core.global.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.*;

/**
 * Base implementation of workflow functional bean
 *
 * @author adiatullin
 */
@Component(WorkflowWorker.NAME)
public class WorkflowWorkerBean extends MessageableBean implements WorkflowWorker {
    private static final Logger log = LoggerFactory.getLogger(WorkflowWorkerBean.class);

    @Inject
    protected DataManager dataManager;
    @Inject
    protected Metadata metadata;
    @Inject
    protected TimeSource timeSource;
    @Inject
    protected Scripting scripting;
    @Inject
    protected JsonUtil jsonUtil;
    @Inject
    protected Persistence persistence;

    @Override
    public UUID startWorkflow(WorkflowEntity entity, Workflow wf) throws WorkflowException {
        Preconditions.checkNotNullArgument(entity, getMessage("WorkflowWorkerBean.emptyEntity"));
        Preconditions.checkNotNullArgument(wf, getMessage("WorkflowWorkerBean.emptyWorkflow"));

        WorkflowInstance instance;
        try {
            wf = reloadNN(wf, "workflow-process");
            if (!Boolean.TRUE.equals(wf.getActive())) {
                throw new WorkflowException(getMessage("WorkflowWorkerBean.workflowNotInActiveState"));
            }

            MetaClass metaClass = metadata.getClassNN(entity.getClass());

            Object entityId = entity.getId();
            if (entityId == null) {
                throw new WorkflowException(String.format(getMessage("WorkflowWorkerBean.incompatibleEntity"), metaClass.getName()));
            }

            if (!Objects.equals(metaClass.getName(), wf.getEntityName())) {
                throw new WorkflowException(String.format(getMessage("WorkflowWorkerBean.workflowIncompatible"),
                        wf.getEntityName(), metaClass.getName()));
            }

            instance = metadata.create(WorkflowInstance.class);
            instance.setWorkflow(wf);
            instance.setEntityName(metaClass.getName());
            instance.setEntityId(entityId.toString());
            instance.setStartDate(timeSource.currentTimestamp());

            entity.setStatus(WorkflowEntityStatus.IN_PROGRESS);
            entity.setStepName(null);

            dataManager.commit(new CommitContext(entity, instance));

            log.info("Workflow {}({}) started for entity {}({}). Workflow instance created {}({})",
                    wf, wf.getId(), metaClass.getName(), entityId, instance, instance.getId());
        } catch (Exception e) {
            if (e instanceof WorkflowException) {
                throw e;
            }
            log.error("Workflow instance processing failed", e);
            throw new WorkflowException(String.format(getMessage("WorkflowWorkerBean.error"), e.getMessage()), e);
        }

        start(instance);

        return instance.getId();
    }

    @Override
    public void restartWorkflow(WorkflowInstance instance) throws WorkflowException {
        Preconditions.checkNotNullArgument(instance, getMessage("WorkflowWorkerBean.emptyWorkflowInstance"));
        try {
            instance = reloadNN(instance, View.LOCAL);
            if (instance.getEndDate() == null) {
                log.warn("Trying to restart already running workflow process instance {}({})", instance, instance.getId());
                return;
            }

            if (StringUtils.isEmpty(instance.getError())) {
                log.warn("Trying to restart success finished workflow process instance {}({})", instance, instance.getId());
                return;
            }

            CommitContext toCommit = new CommitContext();

            instance.setError(null);
            instance.setEndDate(null);
            toCommit.addInstanceToCommit(instance);

            WorkflowEntity entity = getWorkflowEntity(instance);
            if (entity != null) {
                entity.setStatus(WorkflowEntityStatus.IN_PROGRESS);
                toCommit.addInstanceToCommit(entity);
            }

            if (Boolean.TRUE.equals(instance.getErrorInTask())) {
                WorkflowInstanceTask task = getLastTask(instance);
                if (task != null) {
                    task.setEndDate(null);
                    toCommit.addInstanceToCommit(task);
                }
            }

            dataManager.commit(toCommit);

            log.info("Workflow instance {}({}) restarted", instance, instance.getId());
        } catch (Exception e) {
            if (e instanceof WorkflowException) {
                throw e;
            }
            log.error("Workflow instance restarting failed", e);
            throw new WorkflowException(String.format(getMessage("WorkflowWorkerBean.failedToRestartWorkflowInstance"),
                    instance, instance.getId(), e.getMessage()));
        }

        start(instance);
    }

    /**
     * Start execution of provided workflow instance
     *
     * @param instance workflow instance
     * @throws WorkflowException in case of any unexpected problems
     */
    protected void start(WorkflowInstance instance) throws WorkflowException {
        iterate(instance);
    }

    /**
     * Check and run workflow by it's steps. Move instance into next steps by it's scheme
     *
     * @param instance workflow instance
     * @throws WorkflowException in case of any unexpected problems
     */
    protected void iterate(WorkflowInstance instance) throws WorkflowException {
        Preconditions.checkNotNullArgument(instance, getMessage("WorkflowWorkerBean.emptyWorkflowInstance"));

        instance = reloadNN(instance, "workflowInstance-process");

        log.debug("Iterating workflow instance {}({})", instance, instance.getId());

        if (instance.getEndDate() != null) {
            log.debug("Workflow instance {}({}) already finished", instance, instance.getId());
            return;
        }

        WorkflowEntity entity = getWorkflowEntity(instance);
        if (entity == null) {
            log.warn("Entity for workflow instance {}({}) not found", instance, instance.getId());
            markAsFailed(instance, null, null, getMessage("WorkflowWorkerBean.entityNotFound"));
            return;
        }

        WorkflowInstanceTask lastTask = getLastTask(instance);
        if (lastTask != null) {
            if (lastTask.getEndDate() != null) {//current task is done
                Step step = lastTask.getStep();
                if (!CollectionUtils.isEmpty(step.getDirections())) {
                    WorkflowExecutionContext context = null;
                    for (StepDirection direction : step.getDirections()) {
                        boolean satisfy;
                        try {
                            if (context == null) {
                                context = getExecutionContext(instance);
                            }
                            satisfy = isSatisfy(direction, instance, entity, context);
                        } catch (Exception e) {
                            markAsFailed(instance, entity, null, e);
                            if (e instanceof WorkflowException) {
                                throw e;
                            }
                            throw new WorkflowException(
                                    String.format(getMessage("WorkflowWorkerBean.failedToEvaluateDirections"), e.getMessage()));
                        }
                        if (satisfy) {
                            createAndExecuteTask(direction.getTo(), instance, entity);
                            return;
                        }
                    }
                    WorkflowException e = new WorkflowException(
                            String.format(getMessage("WorkflowWorkerBean.noSuitableDirections"),
                                    instance, instance.getId(), step.getStage().getName(), lastTask.getId()));
                    markAsFailed(instance, entity, null, e);
                    throw e;
                } else {//no directions - this is the last step of workflow
                    markAsDone(instance, entity);
                }
            } else {//last task not done - execute it again
                executeTask(lastTask, instance, entity);
            }
        } else {//no task find, this is first iteration call
            Step step = getFirstStep(instance);
            if (step != null) {
                createAndExecuteTask(step, instance, entity);
            } else {//no steps found just done this workflow
                markAsDone(instance, entity);
            }
        }
    }

    /**
     * Check what current direction are suitable to move
     */
    protected boolean isSatisfy(StepDirection direction, WorkflowInstance instance,
                                WorkflowEntity entity, WorkflowExecutionContext context) throws WorkflowException {
        if (!StringUtils.isEmpty(direction.getConditionGroovyScript())) {
            return checkDirectionByGroovy(direction, instance, entity, context);
        } else if (!StringUtils.isEmpty(direction.getConditionSqlScript())) {
            return checkDirectionBySql(direction, instance, entity);
        }
        return true;
    }

    protected boolean checkDirectionByGroovy(StepDirection direction, WorkflowInstance instance,
                                             WorkflowEntity entity, WorkflowExecutionContext context) throws WorkflowException {
        try {
            final String script = direction.getConditionGroovyScript();
            final Map<String, Object> binding = new HashMap<>();
            binding.put("entity", entity);
            binding.put("context", context.getParams());
            binding.put("workflowInstance", instance);

            return Boolean.TRUE.equals(scripting.evaluateGroovy(script, binding));
        } catch (Exception e) {
            log.error(String.format("Failed to evaluate groovy condition direction from %s to %s of workflow instance %s (%s)",
                    direction.getFrom(), direction.getTo(), instance, instance.getId()), e);

            throw new WorkflowException(String.format(getMessage("WorkflowWorkerBean.failedToEvaluateGroovyCondition"),
                    direction.getFrom(), direction.getTo(), instance, instance.getId(), e.getMessage()), e);
        }
    }

    protected boolean checkDirectionBySql(StepDirection direction, WorkflowInstance instance, WorkflowEntity entity) throws WorkflowException {
        try {
            MetaClass metaClass = metadata.getClassNN(instance.getEntityName());

            QueryTransformer transformer = QueryTransformerFactory.createTransformer("select e from " + metaClass.getName() + " e");
            transformer.addWhere(direction.getConditionSqlScript());

            //noinspection unchecked
            List list = dataManager.loadList(LoadContext.create(metaClass.getJavaClass())
                    .setQuery(new LoadContext.Query(transformer.getResult() + " and e.id = :id")
                            .setParameter("id", entity.getId())
                            .setMaxResults(1))
                    .setView(View.MINIMAL));
            return !CollectionUtils.isEmpty(list);
        } catch (Exception e) {
            log.error(String.format("Failed to evaluate sql condition direction from %s to %s of workflow instance %s (%s)",
                    direction.getFrom(), direction.getTo(), instance, instance.getId()), e);

            throw new WorkflowException(String.format(getMessage("WorkflowWorkerBean.failedToEvaluateSqlCondition"),
                    direction.getFrom(), direction.getTo(), instance, instance.getId(), e.getMessage()), e);
        }
    }

    /**
     * Create new task for step and execute it
     */
    protected void createAndExecuteTask(Step step, WorkflowInstance instance, WorkflowEntity entity) throws WorkflowException {
        WorkflowInstanceTask task = metadata.create(WorkflowInstanceTask.class);
        task.setStartDate(timeSource.currentTimestamp());
        task.setInstance(instance);
        task.setStep(step);

        entity.setStatus(WorkflowEntityStatus.IN_PROGRESS);
        entity.setStepName(step.getStage().getName());

        dataManager.commit(new CommitContext(task, entity));

        executeTask(task, instance, entity);
    }

    /**
     * Run workflow task logic, if task step contains algoritm execution, execute it intermediately
     */
    protected void executeTask(WorkflowInstanceTask task, WorkflowInstance instance, WorkflowEntity entity) throws WorkflowException {
        Stage stage = task.getStep().getStage();
        if (StageType.ALGORITHM_EXECUTION.equals(stage.getType())) {//can be executed automatically
            stage = reloadNN(stage, "stage-process");

            boolean executed = true;
            if (!StringUtils.isEmpty(stage.getExecutionGroovyScript())) {
                try {
                    final String script = stage.getExecutionGroovyScript();

                    final Map<String, Object> binding = new HashMap<>();
                    WorkflowExecutionContext context = getExecutionContext(instance);
                    binding.put("entity", reloadNN(entity, View.LOCAL));
                    binding.put("context", context.getParams());
                    binding.put("workflowInstance", reloadNN(instance, View.LOCAL));
                    binding.put("workflowInstanceTask", reloadNN(task, View.LOCAL));

                    //if script returned true - this mean step successfully finished and we can move to the next stage
                    executed = Boolean.TRUE.equals(scripting.evaluateGroovy(script, binding));
                    if (executed) {
                        setExecutionContext(context, instance);
                    }
                } catch (Exception e) {
                    log.error(String.format("Failed to evaluate groovy of workflow instance %s(%s) step %s (%s)",
                            instance, instance.getId(), stage.getName(), task.getId()), e);

                    markAsFailed(instance, entity, task, e);

                    throw new WorkflowException(String.format(getMessage("WorkflowWorkerBean.errorInTask"),
                            stage.getName(), e.getMessage()), e);
                }
            }

            if (executed) {
                finishTask(task);
            }
        }
    }


    @Override
    public void finishTask(WorkflowInstanceTask task) throws WorkflowException {
        finishTask(task, null);
    }

    @Override
    public void finishTask(WorkflowInstanceTask task, @Nullable Map<String, String> params) throws WorkflowException {
        Preconditions.checkNotNullArgument(task, getMessage("WorkflowWorkerBean.emptyWorkflowInstanceTask"));

        WorkflowInstance instance;
        task = reloadNN(task, "workflowInstanceTask-process");
            if (task.getEndDate() != null) {
                throw new WorkflowException(String.format(getMessage("WorkflowWorkerBean.workflowInstanceTaskAlreadyFinished"), task));
            }
            task.setEndDate(timeSource.currentTimestamp());

            instance = task.getInstance();

            if (params != null && params.size() > 0) {
                WorkflowExecutionContext ctx = getExecutionContext(instance);
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    ctx.putParam(entry.getKey(), entry.getValue());
                }
                setExecutionContext(ctx, instance);
            }
        dataManager.commit(task);
        iterate(instance);//move to the next step
    }

    /**
     * Workflow finished successful, mark it's as done and related entity
     */
    protected void markAsDone(WorkflowInstance instance, @Nullable WorkflowEntity entity) throws WorkflowException {
        try (Transaction tr = persistence.getTransaction()) {

            instance = reloadNN(instance, View.LOCAL);

            instance.setEndDate(timeSource.currentTimestamp());

            if (entity != null) {
                entity = reloadNN(entity, View.LOCAL);
                entity.setStatus(WorkflowEntityStatus.DONE);
            }

            tr.commit();
        } catch (Exception e) {
            log.error(String.format("Failed to mark as done workflow instance %s (%s)", instance, instance.getId()), e);
            throw new WorkflowException(String.format(getMessage("WorkflowWorkerBean.failedToDoneWorkflow"), instance, instance.getId()), e);
        }
    }

    /**
     * Workflow finished unsuccessful, mark it as failed and all related entities
     */
    protected void markAsFailed(WorkflowInstance instance, @Nullable WorkflowEntity entity,
                                @Nullable WorkflowInstanceTask task, @Nullable Exception e) throws WorkflowException {
        markAsFailed(instance, entity, task, e == null ? null : ExceptionUtils.getFullStackTrace(e));
    }

    protected void markAsFailed(WorkflowInstance instance, @Nullable WorkflowEntity entity,
                                @Nullable WorkflowInstanceTask task, @Nullable String error) throws WorkflowException {
        try (Transaction tr = persistence.getTransaction()) {
            instance = reloadNN(instance, View.LOCAL);
            instance.setEndDate(timeSource.currentTimestamp());
            instance.setError(StringUtils.isEmpty(error) ? getMessage("WorkflowWorkerBean.internalServerError") : error);
            instance.setErrorInTask(task != null);

            if (entity != null) {
                entity = reloadNN(entity, View.LOCAL);
                entity.setStatus(WorkflowEntityStatus.FAILED);
            }

            if (task != null) {
                task = reloadNN(task, View.LOCAL);
                task.setEndDate(timeSource.currentTimestamp());
            }

            tr.commit();
        } catch (Exception e) {
            log.error(String.format("Failed to mark as failed workflow instance %s (%s). Original error %s",
                    instance, instance.getId(), error), e);
            throw new WorkflowException(String.format(getMessage("WorkflowWorkerBean.failedToWriteException"), instance, instance.getId()), e);
        }
    }

    @Override
    public WorkflowExecutionContext getExecutionContext(WorkflowInstance instance) {
        Preconditions.checkNotNullArgument(instance, getMessage("WorkflowWorkerBean.emptyWorkflowInstance"));

        instance = reloadNN(instance, "workflowInstance-process");
        WorkflowExecutionContext ctx;

        if (!StringUtils.isEmpty(instance.getContext())) {
            ctx = jsonUtil.fromJson(instance.getContext(), WorkflowExecutionContext.class);
        } else {
            ctx = new WorkflowExecutionContext();
        }
        return ctx;

    }

    @Override
    public void setExecutionContext(WorkflowExecutionContext context, WorkflowInstance instance) {
        Preconditions.checkNotNullArgument(instance, getMessage("WorkflowWorkerBean.emptyWorkflowInstance"));

        instance = reloadNN(instance, "workflowInstance-process");

        String text = context == null ? null : jsonUtil.toJson(context);
        if (!Objects.equals(instance.getContext(), text)) {
            instance.setContext(text);
        }
        dataManager.commit(instance);
    }

    @Nullable
    @Override
    public String getParameter(WorkflowInstance instance, @Nullable String key) {
        Preconditions.checkNotNullArgument(instance, getMessage("WorkflowWorkerBean.emptyWorkflowInstance"));
        return getExecutionContext(instance).getParam(key);
    }

    @Override
    public void setParameter(WorkflowInstance instance, @Nullable String key, @Nullable String value) {
        Preconditions.checkNotNullArgument(instance, getMessage("WorkflowWorkerBean.emptyWorkflowInstance"));
        try (Transaction tr = persistence.getTransaction()) {
            WorkflowExecutionContext ctx = getExecutionContext(instance);
            ctx.putParam(key, value);
            setExecutionContext(ctx, instance);

            tr.commit();
        }
    }

    //utils

    /**
     * Retrieve from database last processing task by workflow instance
     */
    @Nullable
    protected WorkflowInstanceTask getLastTask(WorkflowInstance instance) {
        List<WorkflowInstanceTask> list = dataManager.loadList(LoadContext.create(WorkflowInstanceTask.class)
                .setQuery(new LoadContext.Query("select e from wfstp$WorkflowInstanceTask e where e.instance.id = :instanceId " +
                        "order by e.createTs desc")
                        .setParameter("instanceId", instance.getId())
                        .setMaxResults(1))
                .setView("workflowInstanceTask-process"));
        if (!CollectionUtils.isEmpty(list)) {
            return list.get(0);
        }
        return null;
    }

    /**
     * Retrieve from database first step by workflow instance
     */
    @Nullable
    protected Step getFirstStep(WorkflowInstance instance) {
        List<Step> list = dataManager.loadList(LoadContext.create(Step.class)
                .setQuery(new LoadContext.Query("select s from wfstp$WorkflowInstance i " +
                        "join i.workflow w " +
                        "join w.steps s " +
                        "where i.id = :instanceId order by s.order asc")
                        .setParameter("instanceId", instance.getId())
                        .setMaxResults(1))
                .setView("step-process"));
        if (!CollectionUtils.isEmpty(list)) {
            return list.get(0);
        }
        return null;
    }

    /**
     * Retrieve from database workflow related entity
     */
    @Nullable
    protected WorkflowEntity getWorkflowEntity(WorkflowInstance instance) {
        if (instance != null) {
            MetaClass metaClass = metadata.getClassNN(instance.getEntityName());
            Object id = parseEntityId(metaClass, instance.getEntityId());
            //noinspection unchecked
            List list = dataManager.loadList(LoadContext.create(metaClass.getJavaClass()).setId(id).setView(View.LOCAL));
            if (!CollectionUtils.isEmpty(list)) {
                return (WorkflowEntity) list.get(0);
            }
        }
        return null;
    }

    /**
     * Reloading entity with provided view
     */
    protected <T extends Entity> T reloadNN(T entity, String view) {
        entity = dataManager.reload(entity, view);
        if (entity == null) {
            throw new RuntimeException(String.format("Failed to reload entity by view %s", view));
        }
        return entity;
    }

    /**
     * Parse entity ID to correct java object
     */
    @Nullable
    protected Object parseEntityId(MetaClass metaClass, String entityId) {
        if (!StringUtils.isEmpty(entityId)) {
            MetaProperty idProperty = metaClass.getPropertyNN("id");
            Class idClass = idProperty.getJavaType();
            if (UUID.class.isAssignableFrom(idClass)) {
                return UuidProvider.fromString(entityId);
            } else if (Integer.class.isAssignableFrom(idClass)) {
                return Integer.valueOf(entityId);
            } else if (Long.class.isAssignableFrom(idClass)) {
                return Long.valueOf(entityId);
            } else if (String.class.isAssignableFrom(idClass)) {
                return entityId;
            } else {
                throw new UnsupportedOperationException(String.format("Unknown entity '%s' id type '%s'", metaClass.getName(), entityId));
            }
        }
        return null;
    }
}
