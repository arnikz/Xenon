/**
 * Copyright 2013 Netherlands eScience Center
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package nl.esciencecenter.xenon.adaptors.schedulers;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.esciencecenter.xenon.XenonException;
import nl.esciencecenter.xenon.XenonPropertyDescription;
import nl.esciencecenter.xenon.adaptors.schedulers.local.LocalSchedulerAdaptor;
import nl.esciencecenter.xenon.adaptors.schedulers.ssh.SshSchedulerAdaptor;
import nl.esciencecenter.xenon.credentials.Credential;
import nl.esciencecenter.xenon.filesystems.FileSystem;
import nl.esciencecenter.xenon.filesystems.Path;
import nl.esciencecenter.xenon.schedulers.InvalidJobDescriptionException;
import nl.esciencecenter.xenon.schedulers.JobDescription;
import nl.esciencecenter.xenon.schedulers.JobStatus;
import nl.esciencecenter.xenon.schedulers.NoSuchQueueException;
import nl.esciencecenter.xenon.schedulers.Scheduler;
import nl.esciencecenter.xenon.schedulers.Streams;

/**
 * Connection to a remote scheduler, implemented by calling command line commands over a ssh connection.
 * 
 */
public abstract class ScriptingScheduler extends Scheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScriptingScheduler.class);

    protected final Scheduler subScheduler;
    protected final FileSystem subFileSystem;
    
    protected final long pollDelay;
    
    protected ScriptingScheduler(String uniqueID, String adaptor, String location, Credential credential,
           boolean supportsBatch, boolean supportsInteractive, Map<String,String> prop, 
           XenonPropertyDescription[] validProperties, String pollDelayProperty) throws XenonException {

    	super(uniqueID, adaptor, location, false, supportsBatch, supportsInteractive, 
    			ScriptingUtils.getProperties(validProperties, location, prop));
    	
    	this.pollDelay = properties.getLongProperty(pollDelayProperty);

        String subJobScheme;
        String subFileScheme;
        String subLocation;
        Map<String, String> subSchedulerProperties;
        
        if (ScriptingUtils.isLocal(location)) {
            subJobScheme = "local";
            subFileScheme = "file";
            subLocation = "/";
            subSchedulerProperties = properties.filter(LocalSchedulerAdaptor.PREFIX).toMap();
        } else {
            subJobScheme = "ssh";
            subFileScheme = "sftp";
            subLocation = location;
            subSchedulerProperties = properties.filter(SshSchedulerAdaptor.PREFIX).toMap();

            //since we expect commands to be done almost instantaneously, we poll quite frequently (local operation anyway)
            subSchedulerProperties.put(SshSchedulerAdaptor.POLLING_DELAY, "100");
        }

        LOGGER.debug("creating sub scheduler for {} adaptor at {}://{}", adaptor, subJobScheme, subLocation);
        
        subScheduler = Scheduler.create(subJobScheme, subLocation, credential, subSchedulerProperties);

        LOGGER.debug("creating file system for {} adaptor at {}://{}", adaptor, subFileScheme, subLocation);
        subFileSystem = FileSystem.create(subFileScheme, subLocation, credential, null);
    }
    
	protected Path getFsEntryPath() {
        return subFileSystem.getEntryPath();
    }

    /**
     * Run a command on the remote scheduler machine.
     * 
     * @param stdin
     *          the text to write to the input of the executable. 
     * @param executable
     *          the executable to run
     * @param arguments
     *          the arguments to the executable
     * @return
     *          a {@link RemoteCommandRunner} that can be used to monitor the running command
     * @throws XenonException
     *          if an error occurs
     */
    public RemoteCommandRunner runCommand(String stdin, String executable, String... arguments) throws XenonException {
        return new RemoteCommandRunner(subScheduler, getAdaptorName(), stdin, executable, arguments);
    }

    /**
     * Run a command until completion. Throw an exception if the command returns a non-zero exit code, or prints to stderr.
      * 
     * @param stdin
     *          the text to write to the input of the executable. 
     * @param executable
     *          the executable to run
     * @param arguments
     *          the arguments to the executable
     * @return
     *          the text produced by the executable on the stdout stream. 
     * @throws XenonException
     *          if an error occurred
     */
    public String runCheckedCommand(String stdin, String executable, String... arguments) throws XenonException {
        RemoteCommandRunner runner = new RemoteCommandRunner(subScheduler, getAdaptorName(), stdin, executable,
                arguments);

        if (!runner.success()) {
        	throw new XenonException(getAdaptorName(), "could not run command \"" + executable + "\" with stdin \"" + stdin
                    + "\" arguments \"" + Arrays.toString(arguments) + "\" at \"" + subScheduler + "\". Exit code = "
                    + runner.getExitCode() + " Output: " + runner.getStdout() + " Error output: " + runner.getStderr());
        }

        return runner.getStdout();
    }

    /**
     * Start an interactive command on the remote machine (usually via ssh).
     * 
     * @param executable
     *          the executable to start
     * @param arguments
     *          the arguments to pass to the executable
     * @return
     *          the job identifier that represents the interactive command 
     * @throws XenonException
     *          if an error occurred
     */    
    public Streams startInteractiveCommand(String executable, String... arguments) throws XenonException {
        JobDescription description = new JobDescription();
        description.setQueueName("unlimited");        
        description.setExecutable(executable);
        description.setArguments(arguments);

        return subScheduler.submitInteractiveJob(description);
    }

    /**
     * Checks if the queue names given are valid, and throw an exception otherwise. Checks against the list of queues when the
     * scheduler was created.
      * 
     * @param givenQueueNames
     *          the queue names to check for validity
     * @throws NoSuchQueueException
     *          if one or more of the queue names is not known in the scheduler
     */
    protected void checkQueueNames(String[] givenQueueNames) throws XenonException {
        //create a hash set with all given queues
        HashSet<String> invalidQueues = new HashSet<>(Arrays.asList(givenQueueNames));

        //remove all valid queues from the set
        invalidQueues.removeAll(Arrays.asList(getQueueNames()));

        //if anything remains, these are invalid. throw an exception with the invalid queues
        if (!invalidQueues.isEmpty()) {
            throw new NoSuchQueueException(getAdaptorName(), "Invalid queues given: "
                    + Arrays.toString(invalidQueues.toArray(new String[invalidQueues.size()])));
        }
    }
    
    /**
     * Wait until a Job is done, or until the give timeout expires (whichever comes first). 
     * 
     * A timeout of 0 will result in an infinite timeout, a negative timeout will result in an exception. 
     * 
     * @param jobIdentifier
     *          the Job to wait for
     * @param timeout
     *          the maximum number of milliseconds to wait, 0 to wait forever, or negative to return immediately.  
     * @return
     *          the status of the job 
     * @throws IllegalArgumentException
     *          if the value to timeout is negative         
     * @throws XenonException
     *          if an error occurs
     */
    public JobStatus waitUntilDone(String jobIdentifier, long timeout) throws XenonException {

    	checkJobIdentifier(jobIdentifier);
    	
        long deadline = Deadline.getDeadline(timeout);
              
        JobStatus status = getJobStatus(jobIdentifier);

        // wait until we are done, or the timeout expires
        while (!status.isDone() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(pollDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return status;
            }
            
            status = getJobStatus(jobIdentifier);
        }

        return status;
    }

    /**
     * Wait until a Job is running (or already done), or until the given timeout expires, whichever comes first. 
     * 
     * A timeout of 0 will result in an infinite timeout. A negative timeout will result in an exception.
     * 
     * @param jobIdentifier
     *          the Job to wait for
     * @param timeout
     *          the maximum number of milliseconds to wait, 0 to wait forever, or negative to return immediately.  
     * @return
     *          the status of the job 
     * @throws IllegalArgumentException
     *          if the value of timeout was negative         
     * @throws XenonException
     *          if an error occurs
     */
    public JobStatus waitUntilRunning(String jobIdentifier, long timeout) throws XenonException {

    	checkJobIdentifier(jobIdentifier);
    	
        long deadline = Deadline.getDeadline(timeout);
        
        JobStatus status = getJobStatus(jobIdentifier);

        // wait until we are done, or the timeout expires
        while (!(status.isRunning() || status.isDone()) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(pollDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return status;
            }
            
            status = getJobStatus(jobIdentifier);
        }

        return status;
    }

    /**
     * Check if the given working directory exists. Useful for schedulers that do not check this (like Slurm)
     * 
     * @param workingDirectory
     *          the working directory (either absolute or relative) as given by the user.
     * @throws XenonException
     *          if workingDirectory does not exist, or an error occurred.
     */
    protected void checkWorkingDirectory(String workingDirectory) throws XenonException {
        if (workingDirectory == null) {
            return;
        }
               
        Path path;
        
        if (workingDirectory.startsWith("/")) {
            path = new Path(workingDirectory);
        } else {
            //make relative path absolute
            path = getFsEntryPath().resolve(workingDirectory);
        }
        if (!subFileSystem.exists(path)) {
            throw new InvalidJobDescriptionException(getAdaptorName(), "Working directory does not exist: " + path);
        }
    }

    public void close() throws XenonException {
    	subScheduler.close();
    	subFileSystem.close();
    }
}