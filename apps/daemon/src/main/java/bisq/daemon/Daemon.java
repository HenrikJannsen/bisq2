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

package bisq.daemon;

import bisq.application.Executable;
import bisq.common.application.Options;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Daemon extends Executable<DaemonApplicationService> {

    /**
     * @param args If a custom config should be passed use program argument `--config=[config name]`
     *             E.g. `--config=seed_node.config`. The .config extension can be omitted.
     *             Support for loaded config from data directory is not yet implemented.
     */
    public static void main(String[] args) {
        new Daemon(args);
    }

    public Daemon(String[] args) {
        super(args);
    }

    @Override
    protected DaemonApplicationService createApplicationService(String[] args) {
        String configFileName = new Options(args).getValue("config")
                .orElse("daemon")
                .replace(".config", "");
        return new DaemonApplicationService(configFileName, args);
    }
}
