/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.desktop.primary.main.content;

import bisq.desktop.common.utils.Layout;
import bisq.desktop.common.utils.Transitions;
import bisq.desktop.common.view.NavigationView;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Pane;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ContentView extends NavigationView<AnchorPane, ContentModel, ContentController> {

    public ContentView(ContentModel model, ContentController controller) {
        super(new AnchorPane(), model, controller);

        root.setPadding(new Insets(33, 67, 67, 67));
        model.getView().addListener((observable, oldValue, newValue) -> {
            Layout.pinToAnchorPane(newValue.getRoot(), 0, 0, 0, 0);

            Parent parent = newValue.getRoot().getParent();
            // At fast navigation changes we might have not removed the new view and would get 
            // an exception, so we remove it from its parent first. The instanceof check for parent only returns true 
            // if parent is not null. 
            if (parent instanceof Pane) {
                ((Pane) parent).getChildren().remove(newValue.getRoot());
                //todo still issues with fadeout animation... need to return the transition and stop it...
            }
            root.getChildren().add(newValue.getRoot());
            Transitions.transitContentViews(oldValue, newValue);
        });
    }

    @Override
    protected void onViewAttached() {
    }

    @Override
    protected void onViewDetached() {
    }
}
