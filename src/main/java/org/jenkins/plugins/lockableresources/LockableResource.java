/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources;

import static java.text.DateFormat.MEDIUM;
import static java.text.DateFormat.SHORT;

import com.infradna.tool.bridge_method_injector.WithBridgeMethods;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Binding;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Queue;
import hudson.model.Queue.Item;
import hudson.model.Queue.Task;
import hudson.model.Run;
import hudson.model.User;
import hudson.tasks.Mailer.UserProperty;
import java.io.Serializable;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean(defaultVisibility = 999)
public class LockableResource extends AbstractDescribableImpl<LockableResource>
  implements Serializable {

  private static final Logger LOGGER = Logger.getLogger(LockableResource.class.getName());
  public static final int NOT_QUEUED = 0;
  private static final int QUEUE_TIMEOUT = 60;
  public static final String GROOVY_LABEL_MARKER = "groovy:";

  private final String name;
  private String description = "";
  private String labels = "";
  private String reservedBy = null;
  private Date reservedTimestamp = null;
  private String note = "";

  /**
   * Track that a currently reserved resource was originally reserved
   * for someone else, or locked for some other job, and explicitly
   * taken away - e.g. for SUT post-mortems while a test job runs.
   * Currently this does not track "who" it was taken from nor intend
   * to give it back - just for bookkeeping and UI button naming.
   * Cleared when the resource is unReserve'd.
   */
  private boolean stolen = false;

  /**
   * We can use arbitrary identifier in a temporary lock (e.g. a commit hash of
   * built/tested sources), and not overwhelm Jenkins with lots of "garbage" locks.
   * Such locks will be automatically removed when freed, if they were not
   * explicitly declared in the Jenkins Configure System page.
   * If an originally ephemeral lock is later defined in configuration, it becomes
   * a usual persistent lock. If a "usual" lock definition is deleted while it is
   * being held, it becomes ephemeral and will disappear when freed.
   */
  private boolean ephemeral;

  private long queueItemId = NOT_QUEUED;
  private String queueItemProject = null;
  private transient Run<?, ?> build = null;
  // Needed to make the state non-transient
  private String buildExternalizableId = null;
  private long queuingStarted = 0;

  private static final long serialVersionUID = 1L;

  /**
   * Was used within the initial implementation of Pipeline functionality using {@link LockStep},
   * but became deprecated once several resources could be locked at once. See queuedContexts in
   * {@link LockableResourcesManager}.
   *
   * @deprecated Replaced with LockableResourcesManager.queuedContexts (since 1.11)
   */
  @Deprecated private List<StepContext> queuedContexts = new ArrayList<>();

  /** @deprecated Use single-argument constructor instead (since 1.8) */
  @Deprecated
  @ExcludeFromJacocoGeneratedReport
  public LockableResource(String name, String description, String labels, String reservedBy, String note) {
    this.name = name;
    this.description = description;
    this.labels = labels;
    this.reservedBy = Util.fixEmptyAndTrim(reservedBy);
    this.note = note;
  }

  @DataBoundConstructor
  public LockableResource(String name) {
    this.name = name;
  }

  protected Object readResolve() {
    if (queuedContexts == null) { // this field was added after the initial version if this class
      queuedContexts = new ArrayList<>();
    }
    return this;
  }

  /** @deprecated Replaced with LockableResourcesManager.queuedContexts (since 1.11) */
  @Deprecated
  @ExcludeFromJacocoGeneratedReport
  public List<StepContext> getQueuedContexts() {
    return this.queuedContexts;
  }

  @DataBoundSetter
  public void setDescription(String description) {
    this.description = description;
  }

  @DataBoundSetter
  public void setLabels(String labels) {
    this.labels = labels;
  }

  @Exported
  public String getName() {
    return name;
  }

  @Exported
  public String getDescription() {
    return description;
  }

  @Exported
  public String getLabels() {
    return labels;
  }

  @Exported
  public String getNote() {
    return this.note;
  }

  @DataBoundSetter
  public void setNote(String note) {
    this.note = note;
  }

  @DataBoundSetter
  public void setEphemeral(boolean ephemeral) {
    this.ephemeral = ephemeral;
  }

  @Exported
  public boolean isEphemeral() {
    return ephemeral;
  }

  public boolean isValidLabel(String candidate, Map<String, Object> params) {
    return labelsContain(candidate);
  }

  private boolean labelsContain(String candidate) {
    return makeLabelsList().contains(candidate);
  }

  private List<String> makeLabelsList() {
    return Arrays.asList(labels.split("\\s+"));
  }

  /**
   * Checks if the script matches the requirement.
   *
   * @param script Script to be executed
   * @param params Extra script parameters
   * @return {@code true} if the script returns true (resource matches).
   * @throws ExecutionException Script execution failed (e.g. due to the missing permissions).
   *     Carries info in the cause
   */
  @Restricted(NoExternalUse.class)
  public boolean scriptMatches(
    @NonNull SecureGroovyScript script, @CheckForNull Map<String, Object> params)
    throws ExecutionException {
    Binding binding = new Binding(params);
    binding.setVariable("resourceName", name);
    binding.setVariable("resourceDescription", description);
    binding.setVariable("resourceLabels", makeLabelsList());
    binding.setVariable("resourceNote", note);
    try {
      Object result =
        script.evaluate(Jenkins.get().getPluginManager().uberClassLoader, binding, null);
      if (LOGGER.isLoggable(Level.FINE)) {
        LOGGER.fine(
          "Checked resource "
            + name
            + " for "
            + script.getScript()
            + " with "
            + binding
            + " -> "
            + result);
      }
      return (Boolean) result;
    } catch (Exception e) {
      throw new ExecutionException(
        "Cannot get boolean result out of groovy expression. See system log for more info", e);
    }
  }

  @Exported
  public Date getReservedTimestamp() {
    return reservedTimestamp == null ? null : new Date(reservedTimestamp.getTime());
  }

  @DataBoundSetter
  public void setReservedTimestamp(final Date reservedTimestamp) {
    this.reservedTimestamp = reservedTimestamp == null ? null : new Date(reservedTimestamp.getTime());
  }

  @Exported
  public String getReservedBy() {
    return reservedBy;
  }

  @Exported
  public boolean isReserved() {
    return reservedBy != null;
  }

  @Exported
  public String getReservedByEmail() {
    if (isReserved()) {
      UserProperty email = null;
      User user = Jenkins.get().getUser(reservedBy);
      if (user != null) email = user.getProperty(UserProperty.class);
      if (email != null) return email.getAddress();
    }
    return null;
  }

  public boolean isQueued() {
    this.validateQueuingTimeout();
    return queueItemId != NOT_QUEUED;
  }

  // returns True if queued by any other task than the given one
  public boolean isQueued(long taskId) {
    this.validateQueuingTimeout();
    return queueItemId != NOT_QUEUED && queueItemId != taskId;
  }

  public boolean isQueuedByTask(long taskId) {
    this.validateQueuingTimeout();
    return queueItemId == taskId;
  }

  public void unqueue() {
    queueItemId = NOT_QUEUED;
    queueItemProject = null;
    queuingStarted = 0;
  }

  @Exported
  public boolean isLocked() {
    return getBuild() != null;
  }

  /**
   * Resolve the lock cause for this resource. It can be reserved or locked.
   *
   * @return the lock cause or null if not locked
   */
  @CheckForNull
  public String getLockCause() {
    final DateFormat format = SimpleDateFormat.getDateTimeInstance(MEDIUM, SHORT);
    final String timestamp = (reservedTimestamp == null ? "<unknown>" : format.format(reservedTimestamp));
    if (isReserved()) {
      return String.format("[%s] is reserved by %s at %s", name, reservedBy, timestamp);
    }
    if (isLocked()) {
      return String.format("[%s] is locked by %s at %s", name, buildExternalizableId, timestamp);
    }
    return null;
  }

  @WithBridgeMethods(value = AbstractBuild.class, adapterMethod = "getAbstractBuild")
  public Run<?, ?> getBuild() {
    if (build == null && buildExternalizableId != null) {
      build = Run.fromExternalizableId(buildExternalizableId);
    }
    return build;
  }

  @Exported
  public String getBuildName() {
    if (getBuild() != null) return getBuild().getFullDisplayName();
    else return null;
  }

  public void setBuild(Run<?, ?> lockedBy) {
    this.build = lockedBy;
    if (lockedBy != null) {
      this.buildExternalizableId = lockedBy.getExternalizableId();
      setReservedTimestamp(new Date());
    } else {
      this.buildExternalizableId = null;
      setReservedTimestamp(null);
    }
  }

  public Task getTask() {
    Item item = Queue.getInstance().getItem(queueItemId);
    if (item != null) {
      return item.task;
    } else {
      return null;
    }
  }

  public long getQueueItemId() {
    this.validateQueuingTimeout();
    return queueItemId;
  }

  public String getQueueItemProject() {
    this.validateQueuingTimeout();
    return this.queueItemProject;
  }

  public void setQueued(long queueItemId) {
    this.queueItemId = queueItemId;
    this.queuingStarted = System.currentTimeMillis() / 1000;
  }

  public void setQueued(long queueItemId, String queueProjectName) {
    this.setQueued(queueItemId);
    this.queueItemProject = queueProjectName;
  }

  private void validateQueuingTimeout() {
    if (queuingStarted > 0) {
      long now = System.currentTimeMillis() / 1000;
      if (now - queuingStarted > QUEUE_TIMEOUT) unqueue();
    }
  }

  @DataBoundSetter
  public void setReservedBy(String userName) {
    this.reservedBy = Util.fixEmptyAndTrim(userName);
  }

  public void setStolen() {
    this.stolen = true;
  }

  @Exported
  public boolean isStolen() {
    return this.stolen;
  }

  public void reserve(String userName) {
    setReservedBy(userName);
    setReservedTimestamp(new Date());
  }

  public void unReserve() {
    setReservedBy(null);
    setReservedTimestamp(null);
    this.stolen = false;
  }

  public void reset() {
    this.unReserve();
    this.unqueue();
    this.setBuild(null);
  }

  /**
   * Copy unconfigurable properties from another instance. Normally, called after "lockable resource" configuration change.
   * @param sourceResource resource with properties to copy from
   */
  public void copyUnconfigurableProperties(final LockableResource sourceResource) {
    if (sourceResource != null) {
      setReservedTimestamp(sourceResource.getReservedTimestamp());
      setNote(sourceResource.getNote());
    }
  }

  /** Tell LRM to recycle this resource, including notifications for
   * whoever may be waiting in the queue so they can proceed immediately.
   * WARNING: Do not use this from inside the lock step closure which
   * originally locked this resource, to avoid nasty surprises!
   * Just stick with unReserve() and close the closure, if needed.
   */
  public void recycle() {
    try {
      List<LockableResource> resources = new ArrayList<>();
      resources.add(this);
      org.jenkins.plugins.lockableresources.LockableResourcesManager.get().
        recycle(resources);
    } catch (Exception e) {
      this.reset();
    }
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((name == null) ? 0 : name.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    LockableResource other = (LockableResource) obj;
    if (name == null) {
      if (other.name != null) return false;
    } else if (!name.equals(other.name)) return false;
    return true;
  }

  @Extension
  public static class DescriptorImpl extends Descriptor<LockableResource> {

    @NonNull
    @Override
    public String getDisplayName() {
      return "Resource";
    }
  }
}
