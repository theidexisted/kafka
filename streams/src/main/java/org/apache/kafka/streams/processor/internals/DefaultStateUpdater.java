/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.errors.TaskCorruptedException;
import org.apache.kafka.streams.processor.TaskId;
import org.apache.kafka.streams.processor.internals.Task.State;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class DefaultStateUpdater implements StateUpdater {

    private final static String BUG_ERROR_MESSAGE = "This indicates a bug. " +
        "Please report at https://issues.apache.org/jira/projects/KAFKA/issues or to the dev-mailing list (https://kafka.apache.org/contact).";

    private class StateUpdaterThread extends Thread {

        private final ChangelogReader changelogReader;
        private final AtomicBoolean isRunning = new AtomicBoolean(true);
        private final Consumer<Set<TopicPartition>> offsetResetter;
        private final Map<TaskId, Task> updatingTasks = new HashMap<>();
        private final Logger log;

        public StateUpdaterThread(final String name,
                                  final ChangelogReader changelogReader,
                                  final Consumer<Set<TopicPartition>> offsetResetter) {
            super(name);
            this.changelogReader = changelogReader;
            this.offsetResetter = offsetResetter;

            final String logPrefix = String.format("%s ", name);
            final LogContext logContext = new LogContext(logPrefix);
            log = logContext.logger(DefaultStateUpdater.class);
        }

        public Collection<Task> getAllUpdatingTasks() {
            return updatingTasks.values();
        }

        public Collection<StandbyTask> getUpdatingStandbyTasks() {
            return updatingTasks.values().stream()
                .filter(t -> !t.isActive())
                .map(t -> (StandbyTask) t)
                .collect(Collectors.toList());
        }

        public boolean onlyStandbyTasksLeft() {
            return !updatingTasks.isEmpty() && updatingTasks.values().stream().allMatch(t -> !t.isActive());
        }

        @Override
        public void run() {
            log.info("State updater thread started");
            try {
                while (isRunning.get()) {
                    try {
                        runOnce();
                    } catch (final InterruptedException interruptedException) {
                        return;
                    }
                }
            } catch (final RuntimeException anyOtherException) {
                handleRuntimeException(anyOtherException);
            } finally {
                clear();
                shutdownGate.countDown();
                log.info("State updater thread shutdown");
            }
        }

        private void runOnce() throws InterruptedException {
            performActionsOnTasks();
            restoreTasks();
            waitIfAllChangelogsCompletelyRead();
        }

        private void performActionsOnTasks() {
            tasksAndActionsLock.lock();
            try {
                for (final TaskAndAction taskAndAction : getTasksAndActions()) {
                    final Task task = taskAndAction.task;
                    final Action action = taskAndAction.action;
                    switch (action) {
                        case ADD:
                            addTask(task);
                            break;
                    }
                }
            } finally {
                tasksAndActionsLock.unlock();
            }
        }

        private void restoreTasks() {
            try {
                changelogReader.restore(updatingTasks);
            } catch (final TaskCorruptedException taskCorruptedException) {
                handleTaskCorruptedException(taskCorruptedException);
            } catch (final StreamsException streamsException) {
                handleStreamsException(streamsException);
            }
            final Set<TopicPartition> completedChangelogs = changelogReader.completedChangelogs();
            final List<Task> activeTasks = updatingTasks.values().stream().filter(Task::isActive).collect(Collectors.toList());
            for (final Task task : activeTasks) {
                maybeCompleteRestoration((StreamTask) task, completedChangelogs);
            }
        }

        private void handleRuntimeException(final RuntimeException runtimeException) {
            log.error("An unexpected error occurred within the state updater thread: " + runtimeException);
            final ExceptionAndTasks exceptionAndTasks = new ExceptionAndTasks(new HashSet<>(updatingTasks.values()), runtimeException);
            updatingTasks.clear();
            failedTasks.add(exceptionAndTasks);
            isRunning.set(false);
        }

        private void handleTaskCorruptedException(final TaskCorruptedException taskCorruptedException) {
            log.info("Encountered task corrupted exception: ", taskCorruptedException);
            final Set<TaskId> corruptedTaskIds = taskCorruptedException.corruptedTasks();
            final Set<Task> corruptedTasks = new HashSet<>();
            for (final TaskId taskId : corruptedTaskIds) {
                final Task corruptedTask = updatingTasks.remove(taskId);
                if (corruptedTask == null) {
                    throw new IllegalStateException("Task " + taskId + " is corrupted but is not updating. " + BUG_ERROR_MESSAGE);
                }
                corruptedTasks.add(corruptedTask);
            }
            failedTasks.add(new ExceptionAndTasks(corruptedTasks, taskCorruptedException));
        }

        private void handleStreamsException(final StreamsException streamsException) {
            log.info("Encountered streams exception: ", streamsException);
            final ExceptionAndTasks exceptionAndTasks;
            if (streamsException.taskId().isPresent()) {
                exceptionAndTasks = handleStreamsExceptionWithTask(streamsException);
            } else {
                exceptionAndTasks = handleStreamsExceptionWithoutTask(streamsException);
            }
            failedTasks.add(exceptionAndTasks);
        }

        private ExceptionAndTasks handleStreamsExceptionWithTask(final StreamsException streamsException) {
            final TaskId failedTaskId = streamsException.taskId().get();
            if (!updatingTasks.containsKey(failedTaskId)) {
                throw new IllegalStateException("Task " + failedTaskId + " failed but is not updating. " + BUG_ERROR_MESSAGE);
            }
            final Set<Task> failedTask = new HashSet<>();
            failedTask.add(updatingTasks.get(failedTaskId));
            updatingTasks.remove(failedTaskId);
            return new ExceptionAndTasks(failedTask, streamsException);
        }

        private ExceptionAndTasks handleStreamsExceptionWithoutTask(final StreamsException streamsException) {
            final ExceptionAndTasks exceptionAndTasks = new ExceptionAndTasks(new HashSet<>(updatingTasks.values()), streamsException);
            updatingTasks.clear();
            return exceptionAndTasks;
        }

        private void waitIfAllChangelogsCompletelyRead() throws InterruptedException {
            if (isRunning.get() && changelogReader.allChangelogsCompleted()) {
                tasksAndActionsLock.lock();
                try {
                    while (tasksAndActions.isEmpty()) {
                        tasksAndActionsCondition.await();
                    }
                } finally {
                    tasksAndActionsLock.unlock();
                }
            }
        }

        private void clear() {
            tasksAndActionsLock.lock();
            restoredActiveTasksLock.lock();
            try {
                tasksAndActions.clear();
                restoredActiveTasks.clear();
            } finally {
                restoredActiveTasksLock.unlock();
                tasksAndActionsLock.unlock();
            }
            changelogReader.clear();
            updatingTasks.clear();
        }

        private List<TaskAndAction> getTasksAndActions() {
            final List<TaskAndAction> tasksAndActionsToProcess = new ArrayList<>(tasksAndActions);
            tasksAndActions.clear();
            return tasksAndActionsToProcess;
        }

        private void addTask(final Task task) {
            if (isStateless(task)) {
                log.debug("Stateless active task " + task.id() + " was added to the state updater");
                addTaskToRestoredTasks((StreamTask) task);
            } else {
                if (task.isActive()) {
                    updatingTasks.put(task.id(), task);
                    log.debug("Stateful active task " + task.id() + " was added to the state updater");
                    changelogReader.enforceRestoreActive();
                } else {
                    updatingTasks.put(task.id(), task);
                    log.debug("Standby task " + task.id() + " was added to the state updater");
                    if (updatingTasks.size() == 1) {
                        changelogReader.transitToUpdateStandby();
                    }
                }
            }
        }

        private boolean isStateless(final Task task) {
            return task.changelogPartitions().isEmpty() && task.isActive();
        }

        private void maybeCompleteRestoration(final StreamTask task,
                                              final Set<TopicPartition> restoredChangelogs) {
            final Collection<TopicPartition> taskChangelogPartitions = task.changelogPartitions();
            if (restoredChangelogs.containsAll(taskChangelogPartitions)) {
                task.completeRestoration(offsetResetter);
                log.debug("Stateful active task " + task.id() + " completed restoration");
                addTaskToRestoredTasks(task);
                updatingTasks.remove(task.id());
                if (onlyStandbyTasksLeft()) {
                    changelogReader.transitToUpdateStandby();
                }
            }
        }

        private void addTaskToRestoredTasks(final StreamTask task) {
            restoredActiveTasksLock.lock();
            try {
                restoredActiveTasks.add(task);
                log.debug("Active task " + task.id() + " was added to the restored tasks");
                restoredActiveTasksCondition.signalAll();
            } finally {
                restoredActiveTasksLock.unlock();
            }
        }
    }

    enum Action {
        ADD
    }

    private static class TaskAndAction {
        public final Task task;
        public final Action action;

        public TaskAndAction(final Task task, final Action action) {
            this.task = task;
            this.action = action;
        }
    }

    private final Time time;
    private final ChangelogReader changelogReader;
    private final Consumer<Set<TopicPartition>> offsetResetter;
    private final Queue<TaskAndAction> tasksAndActions = new LinkedList<>();
    private final Lock tasksAndActionsLock = new ReentrantLock();
    private final Condition tasksAndActionsCondition = tasksAndActionsLock.newCondition();
    private final Queue<StreamTask> restoredActiveTasks = new LinkedList<>();
    private final Lock restoredActiveTasksLock = new ReentrantLock();
    private final Condition restoredActiveTasksCondition = restoredActiveTasksLock.newCondition();
    private final BlockingQueue<ExceptionAndTasks> failedTasks = new LinkedBlockingQueue<>();
    private CountDownLatch shutdownGate;

    private StateUpdaterThread stateUpdaterThread = null;

    public DefaultStateUpdater(final ChangelogReader changelogReader,
                               final Consumer<Set<TopicPartition>> offsetResetter,
                               final Time time) {
        this.changelogReader = changelogReader;
        this.offsetResetter = offsetResetter;
        this.time = time;
    }

    @Override
    public void add(final Task task) {
        if (stateUpdaterThread == null) {
            stateUpdaterThread = new StateUpdaterThread("state-updater", changelogReader, offsetResetter);
            stateUpdaterThread.start();
            shutdownGate = new CountDownLatch(1);
        }

        verifyStateFor(task);

        tasksAndActionsLock.lock();
        try {
            tasksAndActions.add(new TaskAndAction(task, Action.ADD));
            tasksAndActionsCondition.signalAll();
        } finally {
            tasksAndActionsLock.unlock();
        }
    }

    private void verifyStateFor(final Task task) {
        if (task.isActive() && task.state() != State.RESTORING) {
            throw new IllegalStateException("Active task " + task.id() + " is not in state RESTORING. " + BUG_ERROR_MESSAGE);
        }
        if (!task.isActive() && task.state() != State.RUNNING) {
            throw new IllegalStateException("Standby task " + task.id() + " is not in state RUNNING. " + BUG_ERROR_MESSAGE);
        }
    }

    @Override
    public void remove(final Task task) {
    }

    @Override
    public Set<StreamTask> getRestoredActiveTasks(final Duration timeout) {
        final long timeoutMs = timeout.toMillis();
        final long startTime = time.milliseconds();
        final long deadline = startTime + timeoutMs;
        long now = startTime;
        final Set<StreamTask> result = new HashSet<>();
        try {
            while (now <= deadline && result.isEmpty()) {
                restoredActiveTasksLock.lock();
                try {
                    while (restoredActiveTasks.isEmpty() && now <= deadline) {
                        final boolean elapsed = restoredActiveTasksCondition.await(deadline - now, TimeUnit.MILLISECONDS);
                        now = time.milliseconds();
                    }
                    result.addAll(restoredActiveTasks);
                    restoredActiveTasks.clear();
                } finally {
                    restoredActiveTasksLock.unlock();
                }
                now = time.milliseconds();
            }
            return result;
        } catch (final InterruptedException e) {
            // ignore
        }
        return result;
    }

    @Override
    public List<ExceptionAndTasks> getFailedTasksAndExceptions() {
        final List<ExceptionAndTasks> result = new ArrayList<>();
        failedTasks.drainTo(result);
        return result;
    }

    @Override
    public Set<Task> getAllTasks() {
        tasksAndActionsLock.lock();
        restoredActiveTasksLock.lock();
        try {
            final Set<Task> allTasks = new HashSet<>();
            allTasks.addAll(tasksAndActions.stream()
                .filter(t -> t.action == Action.ADD)
                .map(t -> t.task)
                .collect(Collectors.toList())
            );
            allTasks.addAll(stateUpdaterThread.getAllUpdatingTasks());
            allTasks.addAll(restoredActiveTasks);
            return Collections.unmodifiableSet(allTasks);
        } finally {
            restoredActiveTasksLock.unlock();
            tasksAndActionsLock.unlock();
        }
    }

    @Override
    public Set<StandbyTask> getStandbyTasks() {
        tasksAndActionsLock.lock();
        try {
            final Set<StandbyTask> standbyTasks = new HashSet<>();
            standbyTasks.addAll(tasksAndActions.stream()
                .filter(t -> t.action == Action.ADD)
                .filter(t -> !t.task.isActive())
                .map(t -> (StandbyTask) t.task)
                .collect(Collectors.toList())
            );
            standbyTasks.addAll(getUpdatingStandbyTasks());
            return Collections.unmodifiableSet(standbyTasks);
        } finally {
            tasksAndActionsLock.unlock();
        }
    }

    public Set<StandbyTask> getUpdatingStandbyTasks() {
        return Collections.unmodifiableSet(new HashSet<>(stateUpdaterThread.getUpdatingStandbyTasks()));
    }

    @Override
    public void shutdown(final Duration timeout) {
        if (stateUpdaterThread != null) {
            stateUpdaterThread.isRunning.set(false);
            stateUpdaterThread.interrupt();
            try {
                if (!shutdownGate.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new StreamsException("State updater thread did not shutdown within the timeout");
                }
                stateUpdaterThread = null;
            } catch (final InterruptedException ignored) {
            }
        }
    }
}
