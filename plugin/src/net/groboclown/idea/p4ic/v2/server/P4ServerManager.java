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

package net.groboclown.idea.p4ic.v2.server;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsConnectionProblem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBusConnection;
import net.groboclown.idea.p4ic.P4Bundle;
import net.groboclown.idea.p4ic.config.P4Config;
import net.groboclown.idea.p4ic.config.P4ConfigProject;
import net.groboclown.idea.p4ic.server.exceptions.P4InvalidClientException;
import net.groboclown.idea.p4ic.server.exceptions.P4InvalidConfigException;
import net.groboclown.idea.p4ic.v2.events.BaseConfigUpdatedListener;
import net.groboclown.idea.p4ic.v2.events.ConfigInvalidListener;
import net.groboclown.idea.p4ic.v2.events.Events;
import net.groboclown.idea.p4ic.v2.server.cache.ClientServerId;
import net.groboclown.idea.p4ic.v2.server.cache.state.AllClientsState;
import net.groboclown.idea.p4ic.v2.server.connection.AlertManager;
import net.groboclown.idea.p4ic.v2.server.connection.ProjectConfigSource;
import net.groboclown.idea.p4ic.v2.server.util.FilePathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The owner for the {@link P4Server} instances.
 */
public class P4ServerManager implements ProjectComponent {
    private static final Logger LOG = Logger.getInstance(P4ServerManager.class);

    private final Project project;
    private final MessageBusConnection appMessageBus;
    private final Map<ClientServerId, P4Server> servers = new HashMap<ClientServerId, P4Server>();
    private final AlertManager alertManager;

    // big note on this lock: posting alerts while inside a lock
    // can cause deadlocks, due to the way the timing works while
    // waiting on the master password to be entered.
    private final Lock serverLock = new ReentrantLock();

    private volatile boolean hasServers = false;
    private volatile boolean connectionsValid = true;


    @NotNull
    public static P4ServerManager getInstance(@NotNull Project project) {
        // a non-registered component can happen when the config is loaded outside a project.
        P4ServerManager ret = project.getComponent(P4ServerManager.class);
        if (ret == null) {
            ret = new P4ServerManager(project);
        }
        return ret;
    }


    /** should only be created by the P4Vcs object. */
    public P4ServerManager(@NotNull final Project project) {
        this.project = project;
        this.alertManager = AlertManager.getInstance();
        this.appMessageBus = ApplicationManager.getApplication().getMessageBus().connect();
    }


    /**
     * Simple request to get the list of servers.  Does not check for
     * online status or initialization.
     *
     * @return
     */
    @NotNull
    public List<P4Server> getOnlineServers() {
        if (!connectionsValid) {
            return Collections.emptyList();
        }
        if (!hasServers) {
            // This happens at startup before the system has loaded,
            // or right after an announcement is sent out, before
            // we had a chance to reload our server connections.
            return Collections.emptyList();
        }
        final List<P4Server> ret;
        serverLock.lock();
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Found server configs " + servers);
            }
            ret = new ArrayList<P4Server>(servers.size());

            for (P4Server server : servers.values()) {
                if (server.isValid()) {
                    ret.add(server);
                }
            }
        } finally {
            serverLock.unlock();
        }
        return ret;
    }


    @NotNull
    public List<P4Server> getServers() {
        if (! connectionsValid) {
            return Collections.emptyList();
        }
        List<P4Server> ret;
        if (! hasServers) {
            // This happens at startup before the system has loaded,
            // or right after an announcement is sent out, before
            // we had a chance to reload our server connections.

            // Make sure this runs outside the lock; it will
            // handle its own locking.

            LOG.info("No server connections known for project " + project.getName() + "; forcing an early reload");
            initializeServers();
        }
        List<P4Server> invalid = new ArrayList<P4Server>();
        serverLock.lock();
        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Found server configs " + servers);
            }
            ret = new ArrayList<P4Server>(servers.size());

            for (P4Server server: servers.values()) {
                if (server.isValid()) {
                    ret.add(server);
                } else {
                    invalid.add(server);
                }
            }
        } finally {
            serverLock.unlock();
        }

        // Break that into two separate blocks, so that the
        // invalid servers are handled on their own.
        Map<ClientServerId, P4Server> updated = new HashMap<ClientServerId, P4Server>();
        Set<ClientServerId> removed = new HashSet<ClientServerId>();
        for (P4Server server : invalid) {
            LOG.info("Reconnecting to " + server.getClientServerId());
            if (LOG.isDebugEnabled()) {
                LOG.debug("Valid? " + server.isValid() + "; disposed? " + server.isDisposed() +
                        "; connection valid? " + server.isConnectionValid());
            }
            if (server.getProject().isDisposed()) {
                removed.add(server.getClientServerId());
            } else {
                try {
                    P4Server updatedServer = new P4Server(server.getProject(), server.getProjectConfigSource());
                    updated.put(updatedServer.getClientServerId(), updatedServer);
                } catch (P4InvalidClientException e) {
                    LOG.info(e);
                    removed.add(server.getClientServerId());
                }
            }
        }

        serverLock.lock();
        try {
            for (ClientServerId clientServerId : removed) {
                servers.remove(clientServerId);
            }
            servers.putAll(updated);

            // reset the has server value, since we've updated the list.
            hasServers = ! servers.isEmpty();
        } finally {
            serverLock.unlock();
        }

        return ret;
    }


    /**
     * @param files files
     * @return the matched mapping of files to the servers.  There might be a "null" server entry, which
     * contains a list of file paths that didn't map to a client.
     */
    @NotNull
    public Map<P4Server, List<FilePath>> mapFilePathsToP4Server(Collection<FilePath> files)
            throws InterruptedException {
        return mapToP4Server(files, FILE_PATH_SERVER_MATCHER);
    }

    @NotNull
    public Map<P4Server, List<VirtualFile>> mapVirtualFilesToP4Server(@NotNull final Collection<VirtualFile> files)
            throws InterruptedException {
        return mapToP4Server(files, VIRTUAL_FILE_SERVER_MATCHER);
    }

    @NotNull
    public Map<P4Server, List<VirtualFile>> mapVirtualFilesToOnlineP4Server(@NotNull final Collection<VirtualFile> files)
            throws InterruptedException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("mapping to servers: " + new ArrayList<VirtualFile>(files));
        }
        if (!connectionsValid) {
            LOG.info("configs not valid");
            return Collections.emptyMap();
        }
        return mapToP4Server(getOnlineServers(), files, VIRTUAL_FILE_SERVER_MATCHER);
    }




    @Nullable
    public P4Server getForFilePath(@NotNull FilePath fp) throws InterruptedException {
        if (connectionsValid) {
            return getServerForPath(getServers(), fp);
        } else {
            LOG.info("configs not valid");
            return null;
        }
    }

    public P4Server getForVirtualFile(@NotNull VirtualFile vf) throws InterruptedException {
        if (connectionsValid) {
            return getServerForPath(getServers(), FilePathUtil.getFilePath(vf));
        } else {
            LOG.info("configs not valid");
            return null;
        }
    }


    @Override
    public void projectOpened() {
        // intentionally empty
    }

    @Override
    public void projectClosed() {
        disposeComponent();
    }

    @Override
    public void initComponent() {

        // The servers need to be loaded initially, but they can't be loaded
        // at this point in time, because the file system isn't fully
        // initialized yet.  So, register a post-startup action.
        StartupManager.getInstance(project).registerPostStartupActivity(new Runnable() {
            @Override
            public void run() {
                initializeServers();
            }
        });

        Events.registerP4ServerAppBaseConfigUpdated(appMessageBus, new BaseConfigUpdatedListener() {
            @Override
            public void configUpdated(@NotNull final Project project,
                    @NotNull final List<ProjectConfigSource> sources) {

                // Connections are potentially invalid.  Because the primary project config may be no longer
                // valid, just mark all of the configs invalid.
                // There may also be new connections.  This keeps it all up-to-date.

                if (ApplicationManager.getApplication().isDispatchThread()) {
                    // Run in the background
                    ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
                        @Override
                        public void run() {
                            updateConfigurations(project, sources);
                        }
                    });
                } else {
                    updateConfigurations(project, sources);
                }
            }
        });

        Events.registerP4ServerAppConfigInvalid(appMessageBus, new ConfigInvalidListener() {
            @Override
            public void configurationProblem(@NotNull final Project project, @NotNull final P4Config config,
                    @NotNull final VcsConnectionProblem ex) {
                final AllClientsState clientState = AllClientsState.getInstance();

                // Connections are temporarily invalid.
                connectionsValid = false;
                serverLock.lock();
                try {
                    // TODO examine whether this is appropriate to keep calling.
                    for (P4Server server : servers.values()) {
                        if (server.getProject().equals(project)) {
                            server.setValid(false);
                            clientState.removeClientState(server.getClientServerId());
                        }
                    }
                } finally {
                    serverLock.unlock();
                }
            }
        });
    }

    @Override
    public void disposeComponent() {
        serverLock.lock();
        try {
            for (P4Server p4Server : servers.values()) {
                // Note: don't remove the server from the cache at this point, because
                // it can be used later
                p4Server.dispose();
            }
        } finally {
            serverLock.lock();
        }
        if (appMessageBus != null) {
            appMessageBus.disconnect();
        }
    }

    @NotNull
    @Override
    public String getComponentName() {
        return "Perforce Server Manager";
    }


    @Nullable
    private static P4Server getServerForPath(@NotNull List<P4Server> servers, @NotNull FilePath file)
            throws InterruptedException {
        int minDepth = Integer.MAX_VALUE;
        P4Server minDepthServer = null;
        for (P4Server server : servers) {
            if (! server.isValid()) {
                LOG.warn("Tried to use an invalid server " + server);
                continue;
            }
            int depth = server.getFilePathMatchDepth(file);
            if (LOG.isDebugEnabled()) {
                LOG.debug(" --- server " + server + " match depth: " + depth);
            }
            if (depth < minDepth && depth >= 0) {
                minDepth = depth;
                minDepthServer = server;
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Matched " + file + " to " + minDepthServer);
        }
        return minDepthServer;
    }


    private void initializeServers() {
        P4ConfigProject cp = P4ConfigProject.getInstance(project);
        final List<ProjectConfigSource> sources;
        try {
            sources = cp.loadProjectConfigSources();
        } catch (P4InvalidConfigException e) {
            LOG.info("source load caused error", e);
            serverLock.lock();
            try {
                servers.clear();
            } finally {
                serverLock.unlock();
            }
            return;
        }

        // If this was inside the lock, it could cause a deadlock if waiting on
        // IDE master password
        Map<ClientServerId, P4Server> newServers = new HashMap<ClientServerId, P4Server>();
        for (ProjectConfigSource source : sources) {
            try {
                final P4Server server = new P4Server(project, source);
                newServers.put(server.getClientServerId(), server);
            } catch (P4InvalidClientException e) {
                alertManager.addWarning(project,
                        P4Bundle.message("errors.no-client.source", source),
                        P4Bundle.message("errors.no-client.source", source),
                        e, new FilePath[0]);
            }
        }
        serverLock.lock();
        try {
            servers.clear();
            servers.putAll(newServers);
        } finally {
            serverLock.unlock();
        }
    }


    private void updateConfigurations(@NotNull final Project project,
            @NotNull final List<ProjectConfigSource> sources) {
        List<Warning> warnings = new ArrayList<Warning>();

        serverLock.lock();
        try {
            final List<P4Server> serverCopy = new ArrayList<P4Server>(servers.values());
            for (P4Server server : serverCopy) {
                if (server.getProject().equals(project)) {
                    server.dispose();
                    boolean foundSource = false;
                    for (ProjectConfigSource source : sources) {
                        if (server.isSameSource(source)) {
                            foundSource = true;
                            try {
                                final P4Server newServer = new P4Server(project, source);
                                servers.put(newServer.getClientServerId(), newServer);
                            } catch (P4InvalidClientException e) {
                                servers.remove(server.getClientServerId());
                                warnings.add(new Warning(project,
                                        P4Bundle.message("errors.no-client.source", source),
                                        P4Bundle.message("errors.no-client.source", source),
                                        e));
                            }
                        }
                    }
                    if (!foundSource) {
                        servers.remove(server.getClientServerId());
                    }
                }
            }
            hasServers = !servers.isEmpty();
        } finally {
            serverLock.unlock();
        }
        connectionsValid = true;

        for (Warning warning : warnings) {
            warning.post(alertManager);
        }
    }

    private interface ServerMatcher<T> {
        @Nullable
        P4Server match(@NotNull List<P4Server> servers, T file) throws InterruptedException;
    }

    private static final ServerMatcher<FilePath> FILE_PATH_SERVER_MATCHER = new ServerMatcher<FilePath>() {
        @Nullable
        @Override
        public P4Server match(@NotNull final List<P4Server> servers, final FilePath file) throws InterruptedException {
            return getServerForPath(servers, file);
        }
    };

    private static final ServerMatcher<VirtualFile> VIRTUAL_FILE_SERVER_MATCHER = new ServerMatcher<VirtualFile>() {
        @Nullable
        @Override
        public P4Server match(@NotNull final List<P4Server> servers, final VirtualFile file)
                throws InterruptedException {
            return getServerForPath(servers, FilePathUtil.getFilePath(file));
        }
    };

    /**
     * @param files files
     * @return the matched mapping of files to the servers.  There might be a "null" server entry, which
     * contains a list of file paths that didn't map to a client.
     */
    @NotNull
    private <T> Map<P4Server, List<T>> mapToP4Server(
            @NotNull Collection<T> files,
            @NotNull ServerMatcher<T> matcher)
            throws InterruptedException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("mapping to servers: " + new ArrayList<T>(files));
        }
        if (!connectionsValid) {
            LOG.info("configs not valid");
            return Collections.emptyMap();
        }
        return mapToP4Server(getServers(), files, matcher);
    }


    /**
     * @param files files
     * @return the matched mapping of files to the servers.  There might be a "null" server entry, which
     * contains a list of file paths that didn't map to a client.
     */
    @NotNull
    private <T> Map<P4Server, List<T>> mapToP4Server(
            @NotNull List<P4Server> servers,
            @NotNull Collection<T> files,
            @NotNull ServerMatcher<T> matcher)
            throws InterruptedException {
        if (servers.isEmpty()) {
            LOG.info("no valid servers registered");
            return Collections.emptyMap();
        }
        Map<P4Server, List<T>> ret = new HashMap<P4Server, List<T>>();
        // Find the shallowest match.
        for (T file : files) {
            P4Server minDepthServer = matcher.match(servers, file);
            List<T> match = ret.get(minDepthServer);
            if (match == null) {
                match = new ArrayList<T>();
                ret.put(minDepthServer, match);
            }
            match.add(file);
        }
        return ret;
    }


    private static class Warning {
        private final Project project;
        private final String title;
        private final String details;
        private final P4InvalidClientException exception;

        Warning(final Project project, final String title, final String details,
                final P4InvalidClientException exception) {
            this.project = project;
            this.title = title;
            this.details = details;
            this.exception = exception;
        }

        private void post(final AlertManager alertManager) {
            alertManager.addWarning(this.project,
                    this.title, this.details, this.exception,
                    new FilePath[0]);
        }
    }
}
