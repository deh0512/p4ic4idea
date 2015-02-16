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
package net.groboclown.idea.p4ic.ui.connection;

import net.groboclown.idea.p4ic.P4Bundle;
import net.groboclown.idea.p4ic.config.EnvP4Config;
import net.groboclown.idea.p4ic.config.ManualP4Config;
import net.groboclown.idea.p4ic.config.P4Config;
import net.groboclown.idea.p4ic.server.exceptions.P4DisconnectedException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;


public class EnvConnectionPanel implements ConnectionPanel {
    private JPanel myRootPanel;

    private void createUIComponents() {
        // place custom component creation code here
    }

    @Override
    public boolean isModified(@NotNull P4Config config) {
        return false;
    }

    @Override
    public String getName() {
        return P4Bundle.message("configuration.connection-choice.picker.env");
    }

    @Override
    public String getDescription() {
        return P4Bundle.message("connection.env.description");
    }

    @Override
    public P4Config.ConnectionMethod getConnectionMethod() {
        return P4Config.ConnectionMethod.DEFAULT;
    }

    @Override
    public void loadSettingsIntoGUI(@NotNull P4Config config) {
        // nothing to do
    }

    @Override
    public void saveSettingsToConfig(@NotNull ManualP4Config config) {
        // do nothing - use the child config instead
    }

    /*
    @Nullable
    @Override
    public P4Config loadChildConfig(@NotNull P4Config config) throws P4DisconnectedException {
        return new EnvP4Config();
    }
    */

}
