/*
 *     Copyright 2015 Jean-Christophe Sirot <sirot@chelonix.com>
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
package org.jenkinsci.plugins.ansible;

import java.io.IOException;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * A builder which wraps an Ansible Ad-Hoc command invocation.
 */
public class AnsibleAdHocCommandBuilder extends Builder {

    public final String ansibleName;

    // SSH settings
    /**
     * The id of the credentials to use.
     */
    public final String credentialsId;

    public final String hostPattern;

    /**
     * Path to the inventory file.
     */
    public final Inventory inventory;

    public final  String module;

    public final String command;

    public final boolean sudo;

    public final String sudoUser;

    public final int forks;

    public final boolean unbufferedOutput;

    public final boolean colorizedOutput;

    public final boolean hostKeyChecking;

    public final String additionalParameters;


    @DataBoundConstructor
    public AnsibleAdHocCommandBuilder(String ansibleName, String hostPattern, Inventory inventory, String module,
                                      String command, String credentialsId, boolean sudo, String sudoUser, int forks,
                                      boolean unbufferedOutput, boolean colorizedOutput, boolean hostKeyChecking,
                                      String additionalParameters)
    {
        this.ansibleName = ansibleName;
        this.hostPattern = hostPattern;
        this.inventory = inventory;
        this.module = module;
        this.command = command;
        this.credentialsId = credentialsId;
        this.sudo = sudo;
        this.sudoUser = sudoUser;
        this.forks = forks;
        this.unbufferedOutput = unbufferedOutput;
        this.colorizedOutput = colorizedOutput;
        this.hostKeyChecking = hostKeyChecking;
        this.additionalParameters = additionalParameters;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {

        try {
            CLIRunner runner = new CLIRunner(build, launcher, listener);
            String exe = AnsibleInstallation.getInstallation(ansibleName).getExecutable(AnsibleCommand.ANSIBLE, launcher);
            AnsibleAdHocCommandInvocation invocation = new AnsibleAdHocCommandInvocation(exe , build, listener);
            invocation.setHostPattern(hostPattern);
            invocation.setInventory(inventory);
            invocation.setModule(module);
            invocation.setModuleCommand(command);
            invocation.setSudo(sudo, sudoUser);
            invocation.setForks(forks);
            invocation.setCredentials(StringUtils.isNotBlank(credentialsId) ?
                CredentialsProvider.findCredentialById(credentialsId, StandardUsernameCredentials.class, build) :
                null);
            invocation.setAdditionalParameters(additionalParameters);
            invocation.setHostKeyCheck(hostKeyChecking);
            invocation.setUnbufferedOutput(unbufferedOutput);
            invocation.setColorizedOutput(colorizedOutput);
            return invocation.execute(runner);
        } catch (IOException ioe) {
            Util.displayIOException(ioe, listener);
            ioe.printStackTrace(listener.fatalError(hudson.tasks.Messages.CommandInterpreter_CommandFailed()));
            return false;
        } catch (AnsibleInvocationException aie) {
            listener.fatalError(aie.getMessage());
            return false;
        }
    }

    @Extension
    public static final class DescriptorImpl extends AbstractAnsibleBuilderDescriptor {

        public DescriptorImpl() {
            super("Invoke Ansible Ad-Hoc Command");
        }

        public FormValidation doCheckHostPattern(@QueryParameter String hostPattern) {
            return checkNotNullOrEmpty(hostPattern, "Host pattern must not be empty");
        }
    }
}
