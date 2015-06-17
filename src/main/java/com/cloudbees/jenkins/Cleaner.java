package com.cloudbees.jenkins;

import com.cloudbees.jenkins.GitHubPushTrigger.DescriptorImpl;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.PeriodicWork;
import hudson.triggers.Trigger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.github.webhook.WebhookManager;

import java.net.URL;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.jenkinsci.plugins.github.util.FluentIterableWrapper.from;
import static org.jenkinsci.plugins.github.util.JobInfoHelpers.associatedNames;
import static org.jenkinsci.plugins.github.util.JobInfoHelpers.withTrigger;

/**
 * Removes post-commit hooks from repositories that we no longer care.
 *
 * This runs periodically in a delayed fashion to avoid hitting GitHub too often.
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class Cleaner extends PeriodicWork {
    /**
     * Queue contains repo names prepared to cleanup.
     * After configure method on job, trigger calls {@link #onStop(AbstractProject)}
     * which converts to repo names with help of contributors.
     *
     * This queue is thread-safe, so any thread can write or
     * fetch names to this queue without additional sync
     */
    private final Queue<GitHubRepositoryName> namesq = new ConcurrentLinkedQueue<GitHubRepositoryName>();

    /**
     * Called when a {@link GitHubPushTrigger} is about to be removed.
     */
    /* package */ void onStop(AbstractProject<?, ?> job) {
        namesq.addAll(GitHubRepositoryNameContributor.parseAssociatedNames(job));
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(3);
    }

    /**
     * Each run this work fetches alive repo names (which has trigger for it)
     * then if names queue is not empty (any job was reconfigured with GH trigger change),
     * next name passed to {@link WebhookManager} with list of active names to check and unregister old hooks
     *
     * @throws Exception
     */
    @Override
    protected void doRun() throws Exception {
        URL url = Trigger.all().get(DescriptorImpl.class).getHookUrl();

        List<AbstractProject> jobs = Jenkins.getInstance().getAllItems(AbstractProject.class);
        List<GitHubRepositoryName> alive = from(jobs)
                .filter(withTrigger(GitHubPushTrigger.class))  // live repos
                .transformAndConcat(associatedNames()).toList();

        while (!namesq.isEmpty()) {
            GitHubRepositoryName name = namesq.poll();

            WebhookManager.forHookUrl(url).unregisterFor(name, alive);
        }
    }

    public static Cleaner get() {
        return PeriodicWork.all().get(Cleaner.class);
    }
}
