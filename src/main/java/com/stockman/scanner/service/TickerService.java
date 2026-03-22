package com.stockman.scanner.service;

import com.stockman.config.ScannerConfig;
import com.stockman.config.ZerodhaConfig;
import com.stockman.scanner.engine.ScannerPipeline;
import com.stockman.scanner.model.TickSnapshot;
import com.stockman.service.ZerodhaService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import com.stockman.scanner.event.ScanUniverseChangedEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Zerodha WebSocket wrapper with a two-tier queue architecture.
 *
 * <p>Architecture:
 * <ol>
 *   <li>KiteTicker callback → {@link TickSnapshot} → main {@link ArrayBlockingQueue} (capacity
 *       from {@code scanner.tick-queue-capacity}, default 10,000). Overflow ticks are dropped
 *       and counted in {@link #droppedTicks}.</li>
 *   <li>Single <em>ticker-ingress</em> thread dequeues from main queue and routes each tick to
 *       one of four per-worker queues keyed by {@code instrumentToken % NUM_WORKERS}.</li>
 *   <li>Four <em>scanner-worker</em> threads each drain their own queue, calling
 *       {@link ScannerPipeline#processTick(TickSnapshot)} serially — no lock contention per
 *       symbol because tokens are consistently hashed to the same worker.</li>
 * </ol>
 *
 * <p>The SDK's {@link KiteTicker} reconnect logic is disabled; we implement our own exponential-
 * backoff reconnect inside the {@code onDisconnected} callback so we can check market hours
 * before reconnecting.
 */
@Service
@Slf4j
public class TickerService {

    private static final int NUM_WORKERS = 4;

    private final ScannerConfig config;
    private final ZerodhaConfig zerodhaConfig;
    private final ZerodhaService zerodhaService;
    private final ScannerPipeline pipeline;
    private final AuthStateManager authStateManager;
    private final ExchangeCalendar exchangeCalendar;
    private final InstrumentRegistry instrumentRegistry;

    // ── Two-tier queues ──────────────────────────────────────────────────────

    private final ArrayBlockingQueue<TickSnapshot> mainQueue;

    @SuppressWarnings("unchecked")
    private final ArrayBlockingQueue<TickSnapshot>[] workerQueues =
            (ArrayBlockingQueue<TickSnapshot>[]) new ArrayBlockingQueue[NUM_WORKERS];

    // ── Thread pools ─────────────────────────────────────────────────────────

    private ExecutorService ingressExecutor;
    private ExecutorService workerExecutor;

    // ── Runtime state ────────────────────────────────────────────────────────

    private volatile KiteTicker ticker;
    private final AtomicReference<Set<Long>> subscribedInstruments = new AtomicReference<>(Set.of());
    private volatile boolean running = false;

    /** Monotonically-increasing count of ticks dropped due to main-queue overflow. */
    private final AtomicLong droppedTicks = new AtomicLong(0);

    // ── Constructor ──────────────────────────────────────────────────────────

    public TickerService(ScannerConfig config,
                         ZerodhaConfig zerodhaConfig,
                         ZerodhaService zerodhaService,
                         ScannerPipeline pipeline,
                         AuthStateManager authStateManager,
                         ExchangeCalendar exchangeCalendar,
                         InstrumentRegistry instrumentRegistry) {
        this.config = config;
        this.zerodhaConfig = zerodhaConfig;
        this.zerodhaService = zerodhaService;
        this.pipeline = pipeline;
        this.authStateManager = authStateManager;
        this.exchangeCalendar = exchangeCalendar;
        this.instrumentRegistry = instrumentRegistry;

        int capacity = config.getTickQueueCapacity();
        this.mainQueue = new ArrayBlockingQueue<>(capacity);
        for (int i = 0; i < NUM_WORKERS; i++) {
            // Per-worker queues each get 1/NUM_WORKERS of total capacity (min 1,000)
            workerQueues[i] = new ArrayBlockingQueue<>(Math.max(capacity / NUM_WORKERS, 1000));
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts the ticker WebSocket connection and ingress/worker threads.
     *
     * <p>Safe to call multiple times: a no-op if already running.
     *
     * @param instruments set of Zerodha instrument tokens to subscribe to
     */
    public synchronized void start(Set<Long> instruments) {
        if (running) {
            log.warn("TickerService already running; ignoring start()");
            return;
        }

        KiteConnect kite = zerodhaService.getActiveKiteConnect();
        if (kite == null) {
            log.error("No active KiteConnect session — cannot start TickerService");
            authStateManager.transitionTo(com.stockman.scanner.model.SignalEnums.AuthState.UNAUTHENTICATED);
            return;
        }

        String accessToken;
        String apiKey;
        try {
            accessToken = kite.getAccessToken();
            apiKey = kite.getApiKey();
        } catch (NullPointerException e) {
            log.error("KiteConnect missing access token or API key — cannot start TickerService");
            return;
        }

        subscribedInstruments.set(Set.copyOf(instruments));
        running = true;
        authStateManager.transitionTo(com.stockman.scanner.model.SignalEnums.AuthState.ACTIVE);

        // Build the KiteTicker
        ticker = new KiteTicker(accessToken, apiKey);
        ticker.setTryReconnection(false); // We handle reconnection ourselves

        // ── Callbacks ────────────────────────────────────────────────────────

        ticker.setOnTickerArrivalListener(this::onTicksReceived);

        ticker.setOnConnectedListener(() -> {
            log.info("KiteTicker connected — subscribing {} instruments", instruments.size());
            Set<Long> toSubscribe = subscribedInstruments.get();
            if (!toSubscribe.isEmpty()) {
                ArrayList<Long> tokenList = new ArrayList<>(toSubscribe);
                ticker.subscribe(tokenList);
                ticker.setMode(tokenList, KiteTicker.modeFull);
            }
        });

        ticker.setOnDisconnectedListener(() -> {
            log.warn("KiteTicker disconnected");
            if (running) {
                authStateManager.transitionTo(com.stockman.scanner.model.SignalEnums.AuthState.DEGRADED);
                scheduleReconnect(instruments, 0);
            }
        });

        ticker.setOnErrorListener(new com.zerodhatech.ticker.OnError() {
            @Override
            public void onError(Exception e) {
                log.error("KiteTicker error: {}", e.getMessage(), e);
            }

            @Override
            public void onError(com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException e) {
                log.error("KiteTicker KiteException: {}", e.getMessage(), e);
            }

            @Override
            public void onError(String s) {
                log.error("KiteTicker error (string): {}", s);
            }
        });

        // ── Start ingress thread ──────────────────────────────────────────────

        ingressExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ticker-ingress");
            t.setDaemon(true);
            return t;
        });
        ingressExecutor.submit(this::runIngress);

        // ── Start worker threads ──────────────────────────────────────────────

        workerExecutor = Executors.newFixedThreadPool(NUM_WORKERS, r -> {
            // Determine which worker this thread is for
            int[] counter = {0};
            Thread t = new Thread(r, "scanner-worker-" + counter[0]++);
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < NUM_WORKERS; i++) {
            final int workerId = i;
            workerExecutor.submit(() -> runWorker(workerId));
        }

        // ── Connect to WebSocket ──────────────────────────────────────────────

        ticker.connect();
        log.info("TickerService started with {} instruments", instruments.size());
    }

    /**
     * Stops the ticker gracefully: disconnects WebSocket, shuts down thread pools.
     */
    public synchronized void stop() {
        if (!running) return;

        running = false;
        log.info("Stopping TickerService...");

        if (ticker != null) {
            try {
                ticker.disconnect();
            } catch (Exception e) {
                log.warn("Error disconnecting ticker: {}", e.getMessage());
            }
            ticker = null;
        }

        shutdownExecutor(ingressExecutor, "ticker-ingress", 2);
        shutdownExecutor(workerExecutor, "scanner-workers", 5);

        log.info("TickerService stopped. Total dropped ticks: {}", droppedTicks.get());
    }

    /**
     * Updates the subscription set at runtime without restarting the connection.
     *
     * <p>If the connection is open, unsubscribes removed instruments, subscribes new ones,
     * and sets full mode on the complete new set.
     */
    public void updateSubscription(Set<Long> newInstruments) {
        Set<Long> newSet = Set.copyOf(newInstruments);
        subscribedInstruments.set(newSet);

        if (ticker != null && ticker.isConnectionOpen() && !newSet.isEmpty()) {
            ArrayList<Long> tokenList = new ArrayList<>(newSet);
            ticker.subscribe(tokenList);
            ticker.setMode(tokenList, KiteTicker.modeFull);
            log.info("Updated subscription to {} instruments", newSet.size());
        }
    }

    /** Listens for scan-universe changes published by the lifecycle manager. */
    @EventListener
    public void onScanUniverseChanged(ScanUniverseChangedEvent event) {
        log.info("ScanUniverseChangedEvent received — updating subscription");
        updateSubscription(event.getInstrumentTokens());
    }

    /** Returns the number of ticks dropped due to main-queue overflow. */
    public long getDroppedTicks() {
        return droppedTicks.get();
    }

    // ── Tick receipt ──────────────────────────────────────────────────────────

    private void onTicksReceived(ArrayList<Tick> ticks) {
        if (ticks == null) return;

        for (Tick tick : ticks) {
            TickSnapshot snapshot = convertTick(tick);
            if (!mainQueue.offer(snapshot)) {
                long dropped = droppedTicks.incrementAndGet();
                if (dropped % 1000 == 1) {
                    log.warn("Main tick queue full — dropped {} ticks so far (latest: token={})",
                            dropped, tick.getInstrumentToken());
                }
            }
        }
    }

    /** Converts the Zerodha SDK {@link Tick} to our internal {@link TickSnapshot}. */
    private TickSnapshot convertTick(Tick tick) {
        // exchangeTimestamp: prefer lastTradedTime when available, fall back to now
        Instant exchangeTs = tick.getLastTradedTime() != null
                ? tick.getLastTradedTime().toInstant()
                : Instant.now();

        // Bid/ask prices are not available in full-mode binary ticks without depth;
        // we leave them as 0.0. Callers should treat 0.0 as "unavailable".
        return new TickSnapshot(
                tick.getInstrumentToken(),
                tick.getLastTradedPrice(),
                tick.getOpenPrice(),
                tick.getHighPrice(),
                tick.getLowPrice(),
                tick.getClosePrice(),      // previous day close
                tick.getVolumeTradedToday(),
                0.0,                       // bidPrice — not directly in binary tick
                0.0,                       // askPrice — not directly in binary tick
                exchangeTs,
                Instant.now()
        );
    }

    // ── Ingress thread ────────────────────────────────────────────────────────

    /**
     * Drains the main queue and routes each tick to the appropriate worker queue
     * based on {@code instrumentToken % NUM_WORKERS}.
     */
    private void runIngress() {
        log.info("Ingress thread started");
        while (running || !mainQueue.isEmpty()) {
            try {
                TickSnapshot tick = mainQueue.poll(100, TimeUnit.MILLISECONDS);
                if (tick == null) continue;

                int workerIdx = (int) (Math.abs(tick.instrumentToken()) % NUM_WORKERS);
                ArrayBlockingQueue<TickSnapshot> wq = workerQueues[workerIdx];
                if (!wq.offer(tick)) {
                    log.debug("Worker queue {} full — dropping tick for token {}",
                            workerIdx, tick.instrumentToken());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Ingress thread interrupted");
                break;
            } catch (Exception e) {
                log.error("Unexpected error in ingress thread: {}", e.getMessage(), e);
            }
        }
        log.info("Ingress thread stopped");
    }

    // ── Worker threads ────────────────────────────────────────────────────────

    /**
     * Drains worker queue {@code id} and calls {@link ScannerPipeline#processTick}
     * for each tick. Single-writer per queue means no synchronisation is needed inside
     * the pipeline for symbols hashed to this partition.
     */
    private void runWorker(int id) {
        log.info("Scanner worker {} started", id);
        ArrayBlockingQueue<TickSnapshot> myQueue = workerQueues[id];

        while (running || !myQueue.isEmpty()) {
            try {
                TickSnapshot tick = myQueue.poll(100, TimeUnit.MILLISECONDS);
                if (tick == null) continue;

                pipeline.processTick(tick);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Worker {} interrupted", id);
                break;
            } catch (Exception e) {
                log.error("Worker {} error processing tick: {}", id, e.getMessage(), e);
            }
        }
        log.info("Scanner worker {} stopped", id);
    }

    // ── Reconnection ──────────────────────────────────────────────────────────

    /**
     * Schedules a reconnect attempt with exponential backoff.
     *
     * @param instruments original subscription set
     * @param attempt     current retry attempt count (0-based)
     */
    private void scheduleReconnect(Set<Long> instruments, int attempt) {
        if (!running) return;

        ScannerConfig.Reconnect rc = config.getReconnect();
        long delaySeconds = computeBackoffSeconds(attempt, rc);

        log.info("Scheduling reconnect in {}s (attempt {})", delaySeconds, attempt + 1);

        Thread reconnectThread = new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(delaySeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (!running) return;

            // Check market hours before reconnecting
            java.time.LocalTime now = java.time.LocalTime.now(exchangeCalendar.getTimeZone());
            if (!exchangeCalendar.isWithinMarketHours(now)) {
                log.info("Outside market hours — skipping reconnect attempt");
                return;
            }

            if (!authStateManager.validateSession(zerodhaService)) {
                log.warn("Session invalid during reconnect — aborting");
                return;
            }

            log.info("Attempting KiteTicker reconnect (attempt {})", attempt + 1);
            try {
                KiteConnect kite = zerodhaService.getActiveKiteConnect();
                if (kite == null) {
                    scheduleReconnect(instruments, attempt + 1);
                    return;
                }

                String accessToken = kite.getAccessToken();
                String apiKey = kite.getApiKey();

                ticker = new KiteTicker(accessToken, apiKey);
                ticker.setTryReconnection(false);
                ticker.setOnTickerArrivalListener(this::onTicksReceived);
                ticker.setOnConnectedListener(() -> {
                    authStateManager.transitionTo(com.stockman.scanner.model.SignalEnums.AuthState.ACTIVE);
                    Set<Long> toSubscribe = subscribedInstruments.get();
                    if (!toSubscribe.isEmpty()) {
                        ArrayList<Long> tokenList = new ArrayList<>(toSubscribe);
                        ticker.subscribe(tokenList);
                        ticker.setMode(tokenList, KiteTicker.modeFull);
                    }
                    log.info("Reconnected — resubscribed {} instruments", toSubscribe.size());
                });
                ticker.setOnDisconnectedListener(() -> {
                    if (running) {
                        authStateManager.transitionTo(com.stockman.scanner.model.SignalEnums.AuthState.DEGRADED);
                        scheduleReconnect(instruments, attempt + 1);
                    }
                });
                ticker.setOnErrorListener(new com.zerodhatech.ticker.OnError() {
                    @Override public void onError(Exception e) { log.error("KiteTicker error: {}", e.getMessage()); }
                    @Override public void onError(com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException e) { log.error("KiteTicker KiteException: {}", e.getMessage()); }
                    @Override public void onError(String s) { log.error("KiteTicker error: {}", s); }
                });
                ticker.connect();
            } catch (Exception e) {
                log.error("Reconnect attempt {} failed: {}", attempt + 1, e.getMessage(), e);
                scheduleReconnect(instruments, attempt + 1);
            }
        }, "ticker-reconnect-" + attempt);
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private long computeBackoffSeconds(int attempt, ScannerConfig.Reconnect rc) {
        double delay = rc.getInitialDelaySeconds() * Math.pow(rc.getMultiplier(), attempt);
        return Math.min((long) delay, rc.getMaxDelaySeconds());
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private void shutdownExecutor(ExecutorService executor, String name, int timeoutSeconds) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("{} did not terminate within {}s — forcing shutdown", name, timeoutSeconds);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
