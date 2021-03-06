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

package net.groboclown.idea.p4ic.compat.idea160;

import net.groboclown.idea.p4ic.compat.CompatManager;
import net.groboclown.idea.p4ic.compat.HistoryCompat;
import net.groboclown.idea.p4ic.compat.UICompat;
import net.groboclown.idea.p4ic.compat.VcsCompat;
import org.jetbrains.annotations.NotNull;

public class CompatManager160 extends CompatManager {
    private final UICompat160 uiCompat = new UICompat160();
    private final VcsCompat160 vcsCompat = new VcsCompat160();
    private final HistoryCompat160 historyCompat = new HistoryCompat160();

    @NotNull
    @Override
    public UICompat getUICompat() {
        return uiCompat;
    }

    @NotNull
    @Override
    public VcsCompat getVcsCompat() {
        return vcsCompat;
    }

    @NotNull
    @Override
    public HistoryCompat getHistoryCompat() {
        return historyCompat;
    }
}
