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

package net.groboclown.idea.p4ic.v2.ui.alerts;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import net.groboclown.idea.p4ic.P4Bundle;
import net.groboclown.idea.p4ic.compat.UICompat;
import net.groboclown.idea.p4ic.extension.P4Vcs;
import net.groboclown.idea.p4ic.v2.server.connection.CriticalErrorHandler;
import net.groboclown.idea.p4ic.v2.server.connection.ServerConnectedController;
import org.jetbrains.annotations.NotNull;

public abstract class AbstractErrorHandler implements CriticalErrorHandler {
    private final Project project;
    private final ServerConnectedController serverConnectedController;
    private final Exception exception;

    AbstractErrorHandler(@NotNull final Project project, @NotNull ServerConnectedController connectedController,
            @NotNull Exception exception) {
        this.project = project;
        this.serverConnectedController = connectedController;
        this.exception = exception;
    }


    @NotNull
    protected Project getProject() {
        return project;
    }

    @NotNull
    protected Exception getException() {
        return exception;
    }

    @NotNull
    protected String getExceptionMessage() {
        return exception.getMessage() == null ? "" : exception.getMessage();
    }

    @NotNull
    protected P4Vcs getVcs() {
        return P4Vcs.getInstance(project);
    }

    public boolean isInvalid() {
        ApplicationManager.getApplication().assertIsDispatchThread();
        return project.isDisposed();
    }

    protected boolean isWorkingOnline() {
        return serverConnectedController.isWorkingOnline();
    }

    protected void tryConfigChange() {
        tryConfigChange(true);
    }

    protected void tryConfigChange(final boolean goOffline) {
        ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
               if (! UICompat.getInstance().editVcsConfiguration(
                       getProject(), getVcs().getConfigurable())) {
                   if (goOffline) {
                       goOffline();
                   }
               }
            }
        });
    }

    static boolean tryConfigChangeFor(@NotNull Project project) {
        return UICompat.getInstance().editVcsConfiguration(project,
                P4Vcs.getInstance(project).getConfigurable());
    }

    protected void goOffline() {
        serverConnectedController.disconnect();
        Messages.showMessageDialog(getProject(),
                P4Bundle.message("dialog.offline.went-offline.message"),
                P4Bundle.message("dialog.offline.went-offline.title"),
                Messages.getInformationIcon());
    }

    protected void connect() {
        serverConnectedController.connect(project);
    }


    protected boolean isAutoOffline() {
        return serverConnectedController.isAutoOffline();
    }
}
