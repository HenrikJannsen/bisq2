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

package bisq.android;

import bisq.common.application.ShutDownHandler;
import bisq.common.encoding.Hex;
import bisq.common.threading.ThreadName;
import bisq.security.keys.KeyBundleService;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;

@Slf4j
public class AndroidAppMain {
    public static void main(String[] args) {
        ThreadName.set(AndroidAppMain.class, "main");
        new AndroidAppMain(args);
    }

    private AndroidApplicationService applicationService;
    private AndroidNodeService androidNodeService;

    public AndroidAppMain() {
        AndroidApplicationService androidApplicationService = AndroidApplicationService.getInitializedInstance();
        KeyBundleService keyBundleService = androidApplicationService.getSecurityService().getKeyBundleService();
        String defaultKeyId = keyBundleService.getDefaultKeyId();
        KeyPair keyPair = keyBundleService.getOrCreateKeyBundle(defaultKeyId).getKeyPair();

        log.info("defaultKeyId={}", defaultKeyId);
        log.info("default pub key as hex={}", Hex.encode(keyPair.getPublic().getEncoded()));

    }

    public AndroidAppMain(String[] args) {
        applicationService = new AndroidApplicationService(args, new ShutDownHandler() {
            @Override
            public void shutdown() {

            }

            @Override
            public void addShutDownHook(Runnable shutDownHandler) {

            }
        });

        applicationService.readAllPersisted().join();
        applicationService.initialize()
                .whenComplete(this::onApplicationServiceInitialized);

        keepRunning();
    }

    private void onApplicationServiceInitialized(Boolean result, Throwable throwable) {
        androidNodeService = new AndroidNodeService(applicationService.getPersistenceService(), applicationService.getSecurityService());
        androidNodeService.initialize();
        log.info("Application service initialized");
    }

    protected void keepRunning() {
        try {
            // Avoid that the main thread is exiting
            Thread.currentThread().join();
        } catch (InterruptedException ignore) {
        }
    }
}
