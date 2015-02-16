/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.groboclown.idea.p4ic.changes;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeList;
import com.intellij.openapi.vcs.changes.ChangeListListener;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.perforce.p4java.core.IChangelist;
import net.groboclown.idea.p4ic.P4Bundle;
import net.groboclown.idea.p4ic.background.Background;
import net.groboclown.idea.p4ic.config.Client;
import net.groboclown.idea.p4ic.extension.P4Vcs;
import net.groboclown.idea.p4ic.server.P4StatusMessage;
import net.groboclown.idea.p4ic.server.ServerExecutor;
import net.groboclown.idea.p4ic.server.exceptions.P4InvalidConfigException;
import org.jetbrains.annotations.NotNull;

import java.util.*;

// FIXME add cancel checks
public class P4ChangelistListener implements ChangeListListener {
    private final static Logger LOG = Logger.getInstance(P4ChangelistListener.class);

    //public static final String CHANGELIST_ADDED = P4Bundle.getString("changelist.synchronize.changelist.add");
    public static final String CHANGELIST_REMOVED = P4Bundle.getString("changelist.synchronize.changelist.removed");
    public static final String CHANGELIST_RENAMED = P4Bundle.getString("changelist.synchronize.changelist.renamed");
    public static final String CHANGES_ADDED = P4Bundle.getString("changelist.synchronize.change.add");
    public static final String CHANGES_REMOVED = P4Bundle.getString("changelist.synchronize.change.removed");
    public static final String CHANGES_MOVED = P4Bundle.getString("changelist.synchronize.change.moved");
    //public static final String CHANGES_COMMENT = P4Bundle.getString("changelist.synchronize.change.comments");

    private final Project myProject;
    private final P4Vcs myVcs;

    public P4ChangelistListener(@NotNull final Project project, @NotNull final P4Vcs vcs) {
        myProject = project;
        myVcs = vcs;
    }

    @Override
    public void changeListAdded(@NotNull final ChangeList list) {
        // Adding a changelist does not automatically create a corresponding
        // Perforce changelist.  It must have files added to it that are
        // Perforce-backed in order for it to become one.
        LOG.debug("changeListAdded: " + list.getName() + "; [" + list.getComment() + "]; " + list.getClass().getSimpleName());
    }

    @Override
    public void changeListRemoved(@NotNull final ChangeList list) {
        LOG.debug("changeListRemoved: " + list.getName() + "; [" + list.getComment() + "]; " + list.getClass().getSimpleName());

        if (list instanceof LocalChangeList && ! P4ChangeListMapping.isDefaultChangelist((LocalChangeList) list)) {
            Background.runInBackground(myProject, CHANGELIST_REMOVED,
                    myVcs.getConfiguration().getUpdateOption(), new Background.ER() {
                        @Override
                        public void run(@NotNull ProgressIndicator indicator) throws Exception {
                            indicator.setFraction(0.1);
                            LOG.debug("Fetching p4 changelist for deleted list");
                            Collection<P4ChangeListId> p4clList = myVcs.getChangeListMapping().
                                    getPerforceChangelists((LocalChangeList) list);
                            indicator.setFraction(0.5);
                            LOG.debug("Fetched " + p4clList);
                            if (p4clList != null) {
                                for (Client client: myVcs.getClients()) {
                                    if (client.isWorkingOffline()) {
                                        continue;
                                    }
                                    for (P4ChangeListId p4cl: p4clList) {
                                        if (p4cl.isIn(client)) {
                                            if (p4cl.isNumberedChangelist()) {
                                                LOG.info("Deleted changelist " + p4cl.getChangeListId());
                                                // Remove the mapping first, in case of a problem
                                                myVcs.getChangeListMapping().removeMapping((LocalChangeList) list);

                                                client.getServer().deleteChangelist(p4cl.getChangeListId());
                                            } else {
                                                // else the find call already removed the mapping, if there was one.
                                                LOG.debug("+mapping not found; already removed");
                                            }
                                        }
                                    }
                                }
                            }
                            indicator.setFraction(0.8);
                        }
                    });
        } else {
            LOG.debug("+ not local; is " + list.getClass().getName());
        }
    }

    @Override
    public void changesRemoved(@NotNull final Collection<Change> changes, @NotNull final ChangeList fromList) {
        LOG.debug("changesRemoved: changes " + changes);
        LOG.debug("changesRemoved: changelist " + fromList.getName() + "; [" + fromList.getComment() + "]; " + fromList.getClass().getSimpleName());

        if (fromList instanceof LocalChangeList) {
            // Move the changes into the default change list

            // This allows moving all the files into their respective default changelist,
            // in case something messed up with the initial mapping of files to
            // a perforce changelist.

            //final P4ChangeListId p4id = myVcs.getChangeListMapping().getPerforceChangelist((LocalChangeList) fromList);
            //if (p4id.isNumberedChangelist()) ...

            Background.runInBackground(myProject, CHANGES_REMOVED, myVcs.getConfiguration().getUpdateOption(),
                    new Background.ER() {
                        @Override
                        public void run(@NotNull ProgressIndicator indicator) throws Exception {
                            indicator.setFraction(0.1);
                            List<FilePath> affected = getPathsFromChanges(changes);
                            indicator.setFraction(0.4);
                            Map<Client, List<FilePath>> filesByServer = myVcs.mapFilePathToClient(affected);
                            double count = 0.0;
                            for (Map.Entry<Client, List<FilePath>> e: filesByServer.entrySet()) {
                                indicator.setFraction(0.6 + (0.4 * (count / (double) filesByServer.size())));
                                count += 1.0;
                                e.getKey().getServer().moveFilesBetweenChangelists(-1, 0, e.getValue());
                            }
                        }
                    });
            // else it's either not a Perforce change, or it's the default change.
        } else {
            LOG.debug("+ not local; is " + fromList.getClass().getName());
        }
    }


    @Override
    public void changesAdded(@NotNull final Collection<Change> changes, @NotNull final ChangeList toList) {
        LOG.debug("changesAdded: changes " + changes);
        LOG.debug("changesAdded: changelist " + toList.getName() + "; [" + toList.getComment() + "]; " + toList.getClass().getSimpleName());

        if (toList instanceof LocalChangeList) {
            final List<FilePath> paths = getPathsFromChanges(changes);
            if (paths.isEmpty()) {
                return;
            }

            final Collection<P4ChangeListId> p4idList = myVcs.getChangeListMapping().getPerforceChangelists((LocalChangeList) toList);
            if (p4idList != null && ! p4idList.isEmpty()) {
                Background.runInBackground(myProject, CHANGES_MOVED, myVcs.getConfiguration().getUpdateOption(), new Background.ER() {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) throws Exception {
                        // Ensure the files are all under the same server.
                        indicator.setFraction(0.1);
                        Map<Client, List<FilePath>> filesByServer = myVcs.mapFilePathToClient(paths);
                        double count = 0.0;
                        List<P4StatusMessage> messages = new ArrayList<P4StatusMessage>();
                        for (Map.Entry<Client, List<FilePath>> e: filesByServer.entrySet()) {
                            indicator.setFraction(0.2 + (0.8 * (count / (double) filesByServer.size())));
                            count += 1.0;
                            Client client = e.getKey();
                            if (client.isWorkingOnline()) {
                                ServerExecutor server = client.getServer();
                                boolean found = false;
                                for (P4ChangeListId p4id : p4idList) {
                                    if (p4id.isIn(client)) {
                                        messages.addAll(server.moveFilesBetweenChangelists(
                                                P4ChangeListMapping.P4_UNKNOWN,
                                                p4id.getChangeListId(), e.getValue()));
                                        found = true;
                                        break;
                                    }
                                }

                                if (!found) {
                                    // create that changelist
                                    IChangelist p4cl = server.createChangelist(toDescription(toList));
                                    myVcs.getChangeListMapping().createMapping((LocalChangeList) toList, client.getConfig(), p4cl);
                                    messages.addAll(server.moveFilesBetweenChangelists(
                                            P4ChangeListMapping.P4_UNKNOWN,
                                            p4cl.getId(), paths));
                                }
                            }
                        }
                        P4StatusMessage.throwIfError(messages, true);
                    }
                });
            } else {
                LOG.debug("Need to create a new p4 changelist");
                Background.runInBackground(myProject, CHANGES_ADDED, myVcs.getConfiguration().getUpdateOption(), new Background.ER() {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) throws Exception {
                        indicator.setFraction(0.1);
                        Map<Client, List<FilePath>> filesByServer = myVcs.mapFilePathToClient(paths);

                        // FIXME allow multiple changelists (one per server) per
                        // IDEA changelist
                        if (filesByServer.size() > 1) {
                            throw new P4InvalidConfigException("Only one server is allowed per IDEA changelist");
                        }

                        List<P4StatusMessage> messages = new ArrayList<P4StatusMessage>();

                        double count = 0.0;
                        for (Map.Entry<Client, List<FilePath>> e : filesByServer.entrySet()) {
                            indicator.setFraction(0.2 + (0.8 * (count / (double) filesByServer.size())));
                            count += 1.0;
                            Client client = e.getKey();
                            IChangelist changeList = client.getServer().createChangelist(toDescription(toList));
                            myVcs.getChangeListMapping().createMapping((LocalChangeList) toList, client.getConfig(), changeList);
                            messages.addAll(client.getServer().
                                    moveFilesBetweenChangelists(
                                            P4ChangeListMapping.P4_UNKNOWN,
                                            changeList.getId(), paths));
                        }

                        // FIXME this requires a screen refresh (not resync) of the changelist,
                        // such as toggling the file list display for the change, in order to
                        // make the decorator draw the new change number.

                        P4StatusMessage.throwIfError(messages, true);
                    }
                });
            }
        } else {
            LOG.debug("+ not local; is " + toList.getClass().getName());
        }
    }

    @Override
    public void changeListChanged(final ChangeList list) {
        LOG.debug("changeListChanged: " + list);
    }

    @Override
    public void changeListRenamed(final ChangeList list, final String oldName) {
        LOG.debug("changeListRenamed: from " + oldName + " to " + list);

        if (Comparing.equal(list.getName(), oldName)) {
            return;
        }
        if (P4ChangeListMapping.DEFAULT_CHANGE_NAME.equals(list.getName())) {
            changeListRemoved(list);
            return;
        }
        if (list instanceof LocalChangeList) {
            final Collection<P4ChangeListId> p4idList = myVcs.getChangeListMapping().getPerforceChangelists((LocalChangeList) list);
            if (p4idList != null) {
                Background.runInBackground(myProject, CHANGELIST_RENAMED, myVcs.getConfiguration().getUpdateOption(), new Background.ER() {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) throws Exception {
                        for (Client client: myVcs.getClients()) {
                            for (P4ChangeListId p4id: p4idList) {
                                if (p4id.isIn(client) && ! p4id.isDefaultChangelist()) {
                                    client.getServer().updateChangelistComment(
                                            p4id.getChangeListId(), toDescription(list));
                                }
                            }
                        }
                    }
                });
            }
        } else {
            LOG.debug("+ not local; is " + list.getClass().getName());
        }
    }

    @Override
    public void changeListCommentChanged(final ChangeList list, final String oldComment) {
        LOG.debug("changeListCommentChanged: " + list);

        // This is the same logic as with the name change.
        changeListRenamed(list, list.getName() + "x");
    }

    @Override
    public void changesMoved(final Collection<Change> changes, final ChangeList fromList, final ChangeList toList) {
        LOG.debug("changesMoved: " + fromList + " to " + toList);

        // This is just like a "changes added" command,
        // in the sense that the old list doesn't matter too much.
        changesAdded(changes, toList);
    }

    @Override
    public void defaultListChanged(final ChangeList oldDefaultList, final ChangeList newDefaultList) {
        LOG.debug("defaultListChanged: " + oldDefaultList + " to " + newDefaultList);
    }

    @Override
    public void unchangedFileStatusChanged() {
        LOG.debug("unchangedFileStatusChanged");
    }

    @Override
    public void changeListUpdateDone() {
        LOG.debug("changeListUpdateDone");
    }

    private boolean isUnderVcs(final FilePath path) {
        // Only files can be under VCS control.
        if (path.isDirectory()) {
            return false;
        }
        final AbstractVcs vcs = ProjectLevelVcsManager.getInstance(myProject).getVcsFor(path);
        return ((vcs != null) && (P4Vcs.VCS_NAME.equals(vcs.getName())));
    }

    private List<FilePath> getPathsFromChanges(final Collection<Change> changes) {
        final List<FilePath> paths = new ArrayList<FilePath>();
        for (Change change : changes) {
            if ((change.getBeforeRevision() != null) && (isUnderVcs(change.getBeforeRevision().getFile()))) {
                FilePath path = change.getBeforeRevision().getFile();
                if (!paths.contains(path)) {
                    paths.add(path);
                }
            }
            if ((change.getAfterRevision() != null) && (isUnderVcs(change.getAfterRevision().getFile()))) {
                final FilePath path = change.getAfterRevision().getFile();
                if (!paths.contains(path)) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }


    static String toDescription(@NotNull ChangeList changeList) {
        StringBuilder sb = new StringBuilder();
        if (changeList.getName().length() > 0) {
            sb.append(changeList.getName());
            if (changeList.getComment() != null && changeList.getComment().length() > 0) {
                sb.append("\n");
            }
        }
        if (changeList.getComment() != null && changeList.getComment().length() > 0) {
            sb.append(changeList.getComment());
        }
        return sb.toString();
    }
}