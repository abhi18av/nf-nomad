package nextflow.nomad.executor
/*
 * Copyright 2023, Stellenbosch University, South Africa
 * Copyright 2022, Center for Medical Genetics, Ghent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.nomadproject.client.models.TaskState
import nextflow.cloud.types.CloudMachineInfo
import nextflow.exception.ProcessUnrecoverableException
import nextflow.executor.BashWrapperBuilder
import nextflow.fusion.FusionAwareTask
import nextflow.processor.TaskBean
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.processor.TaskStatus
import nextflow.trace.TraceRecord

import java.nio.file.Path


/**
 * Implements a task handler for Nomad executor
 *
 * @author Abhinav Sharma <abhi18av@outlook.com>
 */

@Slf4j
@CompileStatic
class NomadTaskHandler extends TaskHandler implements FusionAwareTask {

    NomadExecutor executor

    private TaskBean taskBean

    private Path exitFile

    private Path outputFile

    private Path errorFile

    private volatile NomadTaskKey taskKey

    private volatile long timestamp

    private volatile TaskState taskState

    private CloudMachineInfo machineInfo

    NomadTaskHandler(TaskRun task, NomadExecutor executor) {
        super(task)
        this.executor = executor
        this.outputFile = task.workDir.resolve(TaskRun.CMD_OUTFILE)
        this.errorFile = task.workDir.resolve(TaskRun.CMD_ERRFILE)
        this.exitFile = task.workDir.resolve(TaskRun.CMD_EXIT)
        validateConfiguration()
    }

    /** only for testing purpose - DO NOT USE */
    protected NomadTaskHandler() {}

    NomadService getNomadService() {
        return executor.nomadService
    }

    void validateConfiguration() {
        if (!task.container) {
            throw new ProcessUnrecoverableException("No container image specified for process $task.name -- Either specify the container to use in the process definition or with 'process.container' value in your config")
        }
    }

    protected BashWrapperBuilder createBashWrapper() {
        fusionEnabled()
                ? fusionLauncher()
                : new NomadScriptLauncher(task.toTaskBean(), executor)
    }

    @Override
    void submit() {
        log.debug "[NOMAD] Submitting task $task.name - work-dir=${task.workDirStr}"
        createBashWrapper().build()

        // submit the task execution
        this.taskKey = nomadService.submitTask(task)

        log.debug "[NOMAD] Submitted task $task.name with taskId=$taskKey"
        // update the status
        this.status = TaskStatus.SUBMITTED
    }

    @Override
    boolean checkIfRunning() {

        if( !taskKey || !isSubmitted() )
            return false
        final state = taskState0(taskKey)
        // note, include complete status otherwise it hangs if the task
        // completes before reaching this check
        final running = state==!TaskState.SERIALIZED_NAME_FAILED
        log.debug "[NOMAD] Task status $task.name taskId=$taskKey; running=$running"
        if( running )
            this.status = TaskStatus.RUNNING
        return running

    }

    @Override
    boolean checkIfCompleted() {
        assert taskKey
        if( !isRunning() )
            return false
        final done = taskState0(taskKey)==TaskState.SERIALIZED_NAME_FINISHED_AT
        if( done ) {
            // finalize the task
            task.exitStatus = readExitFile()
            task.stdout = outputFile
            task.stderr = errorFile
            status = TaskStatus.COMPLETED
            TaskExecutionInformation info = nomadService.getTask(taskKey).executionInfo()
            if (info.result() == TaskExecutionResult.FAILURE)
                task.error = new ProcessUnrecoverableException(info.failureInfo().message())
            deleteTask(taskKey, task)
            return true
        }
        return false

    }

    private Boolean shouldDelete() {
        //executor.config.batch().deleteJobsOnCompletion
    }

    protected void deleteTask(NomadTaskKey taskKey, TaskRun task) {
        if( !taskKey || shouldDelete()==Boolean.FALSE )
            return

        if( !task.isSuccess() && shouldDelete()==null ) {
            // do not delete successfully executed pods for debugging purpose
            return
        }

        try {
            nomadService.deleteTask(taskKey)
        }
        catch( Exception e ) {
            log.warn "Unable to cleanup nomad task: $taskKey -- see the log file for details", e
        }
    }

    /**
     * @return Retrieve the task status caching the result for at lest one second
     */
    protected TaskState taskState0(NomadTaskKey key) {
        final now = System.currentTimeMillis()
        final delta =  now - timestamp;
        if( !taskState || delta >= 1_000) {
            def newState = nomadService.getTask(key).state()
            log.trace "[NOMAD] Task: $key state=$newState"
            if( newState ) {
                taskState = newState
                timestamp = now
            }
        }
        return taskState
    }


    protected int readExitFile() {
        try {
            exitFile.text as Integer
        }
        catch (Exception e) {
            log.debug "[NOMAD] Cannot read exit status for task: `$task.name` | ${e.message}"
            return Integer.MAX_VALUE
        }
    }

    @Override
    void kill() {
        if( !taskKey )
            return
        nomadService.terminate(taskKey)
    }

    @Override
    TraceRecord getTraceRecord() {
        def result = super.getTraceRecord()
        if( taskKey ) {
            result.put('native_id', taskKey.keyPair())
            result.machineInfo = getMachineInfo()
        }
        return result
    }

    protected CloudMachineInfo getMachineInfo() {
        if( machineInfo )
            return machineInfo
        if( taskKey ) {
            machineInfo = nomadService.machineInfo(taskKey)
            log.trace "[NOMAD] task=$taskKey => machineInfo=$machineInfo"
        }
        return machineInfo
    }

}

