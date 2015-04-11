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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Project;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ArgumentListBuilder;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.ansible.Inventory.InventoryDescriptor;
import org.jenkinsci.plugins.ansible.Inventory.InventoryHandler;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * A builder which wraps an Ansible invocation.
 */
public class AnsibleBuilder extends Builder {

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


    @DataBoundConstructor
    public AnsibleBuilder(String ansibleName, String hostPattern, Inventory inventory, String module, String command,
                          String credentialsId, boolean sudo, String sudoUser, int forks, boolean unbufferedOutput,
                          boolean colorizedOutput, boolean hostKeyChecking)
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
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        AnsibleInstallation installation = getInstallation();
        EnvVars envVars = build.getEnvironment(listener);
        File key = null;
        String exe = installation.getExecutable(AnsibleCommand.ANSIBLE, launcher);
        if (exe == null) {
            listener.fatalError("Ansible executable not found, check your installation.");
            return false;
        }
        ArgumentListBuilder args = new ArgumentListBuilder();

        Map<String, String> env = buildEnvironment();

        String hostPattern = envVars.expand(this.hostPattern);
        String module = envVars.expand(this.module);
        String command = envVars.expand(this.command);
        String sudoUser = envVars.expand(this.sudoUser);

        InventoryHandler inventoryHandler = inventory.getHandler();

        args.add(exe);
        args.add(hostPattern);
        inventoryHandler.addArgument(args, envVars, listener);

        if (module != null && ! module.isEmpty()) {
            args.add("-m").add(module);
        }

        if (command != null && ! command.isEmpty()) {
            args.add("-a").add(command);
        }

        if (sudo) {
            args.add("-S");
            if (sudoUser != null && !sudoUser.isEmpty()) {
                args.add("-R").add(sudoUser);
            }
        }

        args.add("-f").add(forks);

        if (credentialsId != null) {
            SSHUserPrivateKey credentials = CredentialsProvider.findCredentialById(credentialsId, SSHUserPrivateKey.class, build);
            key = createSshKeyFile(key, credentials);
            args.add("--private-key").add(key);
        }

        try {
            if (launcher.launch().pwd(build.getWorkspace()).envs(env).cmds(args).stdout(listener).join() != 0) {
                return false;
            }
        } catch (IOException ioe) {
            Util.displayIOException(ioe, listener);
            ioe.printStackTrace(listener.fatalError(hudson.tasks.Messages.CommandInterpreter_CommandFailed()));
            return false;
        } finally {
            inventoryHandler.tearDown(listener);
            Utils.deleteTempFile(key, listener);
        }
        return true;
    }

    private Map<String, String> buildEnvironment() {
        Map<String, String> env = new HashMap<String, String>();
        if (unbufferedOutput) {
            env.put("PYTHONUNBUFFERED", "1");
        }
        if (colorizedOutput) {
            env.put("ANSIBLE_FORCE_COLOR", "true");
        }
        if (! hostKeyChecking) {
            env.put("ANSIBLE_HOST_KEY_CHECKING", "False");
        }
        return env;
    }

    private File createSshKeyFile(File key, SSHUserPrivateKey credentials) throws IOException, InterruptedException {
        key = File.createTempFile("ssh", "key");
        PrintWriter w = new PrintWriter(key);
        List<String> privateKeys = credentials.getPrivateKeys();
        for (String s : privateKeys) {
            w.println(s);
        }
        w.close();
        new FilePath(key).chmod(0400);
        return key;
    }

    public AnsibleInstallation getInstallation() throws IOException {
        if (ansibleName == null) {
            if (AnsibleInstallation.allInstallations().length == 0) {
                throw new IOException("Ansible not found");
            }
            return AnsibleInstallation.allInstallations()[0];
        } else {
            for (AnsibleInstallation installation: AnsibleInstallation.allInstallations()) {
                if (ansibleName.equals(installation.getName())) {
                    return installation;
                }
            }
        }
        throw new IOException("Ansible not found");
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public DescriptorImpl() {
            load();
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Project project) {
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(CredentialsMatchers.instanceOf(SSHUserPrivateKey.class),
                            CredentialsProvider.lookupCredentials(StandardUsernameCredentials.class, project));
        }

        public List<InventoryDescriptor> getInventories() {
            return Jenkins.getInstance().getDescriptorList(Inventory.class);
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> klass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Invoke Ansible";
        }

        public AnsibleInstallation[] getInstallations() {
            return AnsibleInstallation.allInstallations();
        }
    }
}