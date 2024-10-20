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
import bisq.common.observable.Observable;
import bisq.common.util.ExceptionUtil;
import bisq.security.SecurityService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.concurrent.CompletableFuture.supplyAsync;

/**
 * Creates domain specific options from program arguments and application options.
 * Creates domain instance with options and optional dependency to other domain objects.
 * Initializes the domain instances according to the requirements of their dependencies either in sequence
 * or in parallel.
 */

@Slf4j
public class AndroidApplicationService extends TempApplicationService {
    public static final long STARTUP_TIMEOUT_SEC = 300;
    public static final long SHUTDOWN_TIMEOUT_SEC = 10;
    private static AndroidApplicationService INSTANCE;

    @Getter
    private final Observable<State> state = new Observable<>(State.INITIALIZE_APP);
    @Getter
    private final Observable<String> shutDownErrorMessage = new Observable<>();
    @Getter
    private final Observable<String> startupErrorMessage = new Observable<>();
    @Getter
    private final SecurityService securityService;

    public static AndroidApplicationService getInitializedInstance(String userDataDir) {
        if (INSTANCE == null) {
            // Androids default BC version does not support all algorithms we need, thus we remove
            // it and add our BC provider
            Security.removeProvider("BC");
            Security.addProvider(new BouncyCastleProvider());

            AndroidApplicationService applicationService = new AndroidApplicationService(userDataDir,
                    new String[]{},
                    new ShutDownHandler() {
                        @Override
                        public void shutdown() {
                        }

                        @Override
                        public void addShutDownHook(Runnable shutDownHandler) {
                        }
                    });
            applicationService.readAllPersisted().join();
            applicationService.initialize().join();
            log.info("Application service initialized");
            INSTANCE = applicationService;
        }
        return INSTANCE;
    }

    public AndroidApplicationService(String userDataDir, String[] args, ShutDownHandler shutDownHandler) {
        super(userDataDir, "android", args);

        securityService = new SecurityService(persistenceService, SecurityService.Config.from(getConfig("security")));

       /* networkService = new NetworkService(NetworkServiceConfig.from(config.getBaseDir(),
                getConfig("network")),
                persistenceService,
                securityService.getKeyBundleService(),
                securityService.getHashCashProofOfWorkService(),
                securityService.getEquihashProofOfWorkService());

        identityService = new IdentityService(persistenceService,
                securityService.getKeyBundleService(),
                networkService);

        bondedRolesService = new BondedRolesService(BondedRolesService.Config.from(getConfig("bondedRoles")),
                getPersistenceService(),
                networkService);

        accountService = new AccountService(persistenceService);

        contractService = new ContractService(securityService);

        userService = new UserService(persistenceService,
                securityService,
                identityService,
                networkService,
                bondedRolesService);

        settingsService = new SettingsService(persistenceService);

        systemNotificationService = new SystemNotificationService(config.getBaseDir(), settingsService);

        offerService = new OfferService(networkService, identityService, persistenceService);

        chatService = new ChatService(persistenceService,
                networkService,
                userService,
                settingsService,
                systemNotificationService);

        supportService = new SupportService(SupportService.Config.from(getConfig("support")),
                persistenceService,
                networkService,
                chatService,
                userService,
                bondedRolesService);

        tradeService = new TradeService(networkService, identityService, persistenceService, offerService,
                contractService, supportService, chatService, bondedRolesService, userService, settingsService);

        updaterService = new UpdaterService(getConfig(), settingsService, bondedRolesService.getReleaseNotificationsService());

        bisqEasyService = new BisqEasyService(persistenceService,
                securityService,
                walletService,
                networkService,
                identityService,
                bondedRolesService,
                accountService,
                offerService,
                contractService,
                userService,
                chatService,
                settingsService,
                supportService,
                systemNotificationService,
                tradeService);

        alertNotificationsService = new AlertNotificationsService(settingsService, bondedRolesService.getAlertService());

        favouriteMarketsService = new FavouriteMarketsService(settingsService);

        dontShowAgainService = new DontShowAgainService(settingsService);*/
    }

    @Override
    public CompletableFuture<Boolean> initialize() {
        return securityService.initialize()
                /*.thenCompose(result -> {
                    setState(State.INITIALIZE_NETWORK);
                    return networkService.initialize();
                })
                .whenComplete((r, throwable) -> {
                    if (throwable == null) {
                        setState(State.INITIALIZE_SERVICES);
                    }
                })
                .thenCompose(result -> identityService.initialize())
                .thenCompose(result -> bondedRolesService.initialize())
                .thenCompose(result -> accountService.initialize())
                .thenCompose(result -> contractService.initialize())
                .thenCompose(result -> userService.initialize())
                .thenCompose(result -> settingsService.initialize())
                .thenCompose(result -> offerService.initialize())
                .thenCompose(result -> chatService.initialize())
                .thenCompose(result -> systemNotificationService.initialize())
                .thenCompose(result -> supportService.initialize())
                .thenCompose(result -> tradeService.initialize())
                .thenCompose(result -> updaterService.initialize())
                .thenCompose(result -> bisqEasyService.initialize())
                .thenCompose(result -> alertNotificationsService.initialize())
                .thenCompose(result -> favouriteMarketsService.initialize())
                .thenCompose(result -> dontShowAgainService.initialize())
                .thenCompose(result -> webcamAppService.initialize())*/
                .orTimeout(STARTUP_TIMEOUT_SEC, TimeUnit.SECONDS)
                .handle((result, throwable) -> {
                    if (throwable == null) {
                        if (result != null && result) {
                            setState(State.APP_INITIALIZED);
                            log.info("ApplicationService initialized");
                            return true;
                        } else {
                            startupErrorMessage.set("Initializing applicationService failed with result=false");
                            log.error(startupErrorMessage.get());
                        }
                    } else {
                        log.error("Initializing applicationService failed", throwable);
                        startupErrorMessage.set(ExceptionUtil.getRootCauseMessage(throwable));
                    }
                    setState(State.FAILED);
                    return false;
                });
    }

    @Override
    public CompletableFuture<Boolean> shutdown() {
        log.info("shutdown");
        // We shut down services in opposite order as they are initialized
        // In case a shutdown method completes exceptionally we log the error and map the result to `false` to not
        // interrupt the shutdown sequence.
        return supplyAsync(() -> securityService.shutdown().exceptionally(this::logError)

                /* return supplyAsync(() -> webcamAppService.shutdown().exceptionally(this::logError)
                         .thenCompose(result -> dontShowAgainService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> favouriteMarketsService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> alertNotificationsService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> bisqEasyService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> updaterService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> tradeService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> supportService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> systemNotificationService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> chatService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> offerService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> settingsService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> userService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> contractService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> accountService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> bondedRolesService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> identityService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> networkService.shutdown().exceptionally(this::logError))
                         .thenCompose(result -> walletService.map(service -> service.shutdown().exceptionally(this::logError))
                                 .orElse(CompletableFuture.completedFuture(true)))
                         .thenCompose(result -> securityService.shutdown().exceptionally(this::logError))*/
                .orTimeout(SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)
                .handle((result, throwable) -> {
                    if (throwable == null) {
                        if (result != null && result) {
                            log.info("ApplicationService shutdown completed");
                            return true;
                        } else {
                            startupErrorMessage.set("Shutdown applicationService failed with result=false");
                            log.error(shutDownErrorMessage.get());
                        }
                    } else {
                        log.error("Shutdown applicationService failed", throwable);
                        shutDownErrorMessage.set(ExceptionUtil.getRootCauseMessage(throwable));
                    }
                    return false;
                })
                .join());
    }

    private void setState(State newState) {
        checkArgument(state.get().ordinal() < newState.ordinal(),
                "New state %s must have a higher ordinal as the current state %s", newState, state.get());
        state.set(newState);
        log.info("New state {}", newState);
    }

    private boolean logError(Throwable throwable) {
        log.error("Exception at shutdown", throwable);
        return false;
    }
}
