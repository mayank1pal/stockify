// Scanner — live signal feed via STOMP/SockJS WebSocket
const Scanner = (() => {
    let stompClient = null;
    let lastSeenId = null;
    let reconnectTimer = null;
    let signalCount = 0;

    // Active filter state
    const filters = {
        style: '',
        strength: '',
        symbol: ''
    };

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    function init() {
        bindFilterEvents();
        loadInitialSignals();
        connect();
    }

    // -------------------------------------------------------------------------
    // WebSocket / STOMP
    // -------------------------------------------------------------------------

    function connect() {
        if (reconnectTimer) {
            clearTimeout(reconnectTimer);
            reconnectTimer = null;
        }

        setConnectionStatus('connecting');

        const socket = new SockJS('/ws');
        stompClient = new StompJs.Client({
            webSocketFactory: () => socket,
            reconnectDelay: 0, // We handle reconnect ourselves
            onConnect: onConnected,
            onDisconnect: onDisconnected,
            onStompError: (frame) => {
                console.error('STOMP error', frame);
                setConnectionStatus('disconnected');
                scheduleReconnect();
            }
        });

        stompClient.activate();
    }

    function onConnected() {
        setConnectionStatus('connected');

        stompClient.subscribe('/topic/signals', (message) => {
            try {
                const signal = JSON.parse(message.body);
                prependSignalCard(signal);
                updateLastSeenId(signal.id || signal.signalId);
                incrementSignalsToday();
            } catch (e) {
                console.error('Failed to parse signal message', e);
            }
        });

        stompClient.subscribe('/topic/ai-enrichment', (message) => {
            try {
                const enrichment = JSON.parse(message.body);
                updateAiInsight(enrichment.signalId, enrichment.insight || enrichment.aiInsight);
            } catch (e) {
                console.error('Failed to parse ai-enrichment message', e);
            }
        });

        stompClient.subscribe('/topic/scanner-status', (message) => {
            try {
                const status = JSON.parse(message.body);
                applyStatusUpdate(status);
            } catch (e) {
                console.error('Failed to parse scanner-status message', e);
            }
        });

        // Fetch any signals missed while disconnected
        if (lastSeenId !== null) {
            fetchMissedSignals(lastSeenId);
        }

        // Also refresh scanner status via REST
        API.getScannerStatus().then(applyStatusUpdate).catch(() => {});
    }

    function onDisconnected() {
        setConnectionStatus('disconnected');
        scheduleReconnect();
    }

    function scheduleReconnect() {
        if (reconnectTimer) return;
        reconnectTimer = setTimeout(() => {
            reconnectTimer = null;
            connect();
        }, 5000);
    }

    // -------------------------------------------------------------------------
    // Initial data load
    // -------------------------------------------------------------------------

    async function loadInitialSignals() {
        try {
            const result = await API.getScannerSignals(null, 50);
            const signals = Array.isArray(result) ? result : (result.signals || result.content || []);

            if (signals.length > 0) {
                // Render oldest first so newest ends up at top after prepend
                [...signals].reverse().forEach((signal) => {
                    prependSignalCard(signal, /* animate */ false);
                });
                updateLastSeenId(signals[0].id || signals[0].signalId);
                updateSignalsCount(signals.length);
            }

            const status = await API.getScannerStatus();
            applyStatusUpdate(status);
        } catch (e) {
            console.warn('Could not load initial scanner signals:', e);
        }
    }

    async function fetchMissedSignals(cursor) {
        try {
            const result = await API.getScannerSignals(cursor, 50);
            const signals = Array.isArray(result) ? result : (result.signals || result.content || []);
            if (signals.length > 0) {
                [...signals].reverse().forEach((signal) => prependSignalCard(signal, false));
                updateLastSeenId(signals[0].id || signals[0].signalId);
            }
        } catch (e) {
            console.warn('Could not fetch missed signals:', e);
        }
    }

    // -------------------------------------------------------------------------
    // Signal card rendering
    // -------------------------------------------------------------------------

    function prependSignalCard(signal, animate = true) {
        const feed = document.getElementById('scanner-feed');
        const empty = document.getElementById('scanner-feed-empty');
        if (empty) empty.style.display = 'none';

        const card = buildSignalCard(signal, animate);
        feed.insertBefore(card, feed.firstChild);

        applyFiltersToCard(card);
    }

    function buildSignalCard(signal, animate) {
        const id = signal.id || signal.signalId || '';
        const symbol = signal.symbol || signal.tradingSymbol || 'UNKNOWN';
        const signalType = signal.signalType || signal.type || 'BUY';
        const strength = signal.strength || 'MODERATE';
        const style = signal.tradingStyle || signal.style || '';
        const timeframe = signal.timeframe || '';
        const entry = signal.entryPrice != null ? formatPrice(signal.entryPrice) : '—';
        const stop = signal.stopLoss != null ? formatPrice(signal.stopLoss) : '—';
        const target = signal.targetPrice != null ? formatPrice(signal.targetPrice) : '—';
        const rr = signal.riskRewardRatio != null ? signal.riskRewardRatio.toFixed(2) : '—';
        const reasons = Array.isArray(signal.triggerReasons) ? signal.triggerReasons : [];
        const timestamp = signal.timestamp || signal.createdAt || new Date().toISOString();

        const isBuy = signalType.includes('BUY');
        const typeClass = signalType === 'STRONG_BUY' ? 'signal-type-strong-buy'
            : signalType === 'BUY' ? 'signal-type-buy'
            : signalType === 'STRONG_SELL' ? 'signal-type-strong-sell'
            : 'signal-type-sell';

        const borderClass = isBuy ? 'signal-card-buy' : 'signal-card-sell';
        const strengthClass = strength === 'STRONG' ? 'signal-strength-strong' : 'signal-strength-moderate';

        const card = document.createElement('div');
        card.className = `signal-card ${borderClass}${animate ? ' animate-in' : ''}`;
        card.dataset.signalId = id;
        card.dataset.style = style.toUpperCase();
        card.dataset.strength = strength.toUpperCase();
        card.dataset.symbol = symbol.toUpperCase();

        card.innerHTML = `
            <div class="signal-card-header">
                <div class="signal-card-title">
                    <span class="signal-type-badge ${typeClass}">${formatSignalType(signalType)}</span>
                    <span class="signal-symbol">${symbol}</span>
                    ${style ? `<span class="signal-style-tag">${style}</span>` : ''}
                    <span class="signal-strength ${strengthClass}">${strength}</span>
                </div>
                <div class="signal-card-meta">
                    ${timeframe ? `<span class="signal-timeframe">${timeframe}</span>` : ''}
                    <span class="signal-timestamp">${formatTime(timestamp)}</span>
                </div>
            </div>

            <div class="signal-prices">
                <div class="signal-price-item">
                    <span class="signal-price-label">Entry</span>
                    <span class="signal-price-value">₹${entry}</span>
                </div>
                <div class="signal-price-item">
                    <span class="signal-price-label">Stop</span>
                    <span class="signal-price-value signal-stop">₹${stop}</span>
                </div>
                <div class="signal-price-item">
                    <span class="signal-price-label">Target</span>
                    <span class="signal-price-value signal-target">₹${target}</span>
                </div>
                <div class="signal-price-item">
                    <span class="signal-price-label">R:R</span>
                    <span class="signal-price-value signal-rr">${rr}</span>
                </div>
            </div>

            ${reasons.length > 0 ? `
            <div class="signal-reasons">
                <span class="signal-reasons-label">Triggers:</span>
                <ul class="signal-reasons-list">
                    ${reasons.map(r => `<li>${escapeHtml(r)}</li>`).join('')}
                </ul>
            </div>` : ''}

            <div class="ai-insight" id="ai-insight-${escapeHtml(id)}">
                <span class="ai-insight-icon">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M12 2a3 3 0 0 0-3 3v1H6a2 2 0 0 0-2 2v2a2 2 0 0 0 2 2h1v3a7 7 0 0 0 14 0v-3h1a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-3V5a3 3 0 0 0-3-3z" />
                        <circle cx="9" cy="13" r="1" />
                        <circle cx="15" cy="13" r="1" />
                    </svg>
                </span>
                <span class="ai-insight-text">${signal.aiInsight ? escapeHtml(signal.aiInsight) : 'AI analysis pending...'}</span>
            </div>
        `;

        return card;
    }

    // -------------------------------------------------------------------------
    // AI enrichment update
    // -------------------------------------------------------------------------

    function updateAiInsight(signalId, insight) {
        if (!signalId || !insight) return;
        const el = document.getElementById(`ai-insight-${signalId}`);
        if (!el) return;
        const textEl = el.querySelector('.ai-insight-text');
        if (textEl) {
            textEl.textContent = insight;
            el.classList.add('ai-insight-updated');
        }
    }

    // -------------------------------------------------------------------------
    // Status bar
    // -------------------------------------------------------------------------

    function applyStatusUpdate(status) {
        if (!status) return;
        if (status.instrumentsTracked != null) {
            document.getElementById('instruments-count').textContent = status.instrumentsTracked;
        }
        if (status.signalsToday != null) {
            document.getElementById('signals-today').textContent = status.signalsToday;
            signalCount = status.signalsToday;
        }
        const now = new Date();
        document.getElementById('last-update').textContent =
            'Updated ' + now.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    }

    function setConnectionStatus(state) {
        const dot = document.getElementById('connection-dot');
        const label = document.getElementById('connection-label');
        if (!dot || !label) return;

        dot.className = 'connection-dot';
        if (state === 'connected') {
            dot.classList.add('connection-dot-connected');
            label.textContent = 'Connected';
        } else if (state === 'connecting') {
            dot.classList.add('connection-dot-connecting');
            label.textContent = 'Connecting...';
        } else {
            dot.classList.add('connection-dot-disconnected');
            label.textContent = 'Disconnected — retrying in 5s';
        }
    }

    // -------------------------------------------------------------------------
    // Filter logic
    // -------------------------------------------------------------------------

    function bindFilterEvents() {
        document.getElementById('filter-style').addEventListener('change', (e) => {
            filters.style = e.target.value;
            applyFiltersToAll();
        });
        document.getElementById('filter-strength').addEventListener('change', (e) => {
            filters.strength = e.target.value;
            applyFiltersToAll();
        });
        document.getElementById('filter-symbol').addEventListener('input', (e) => {
            filters.symbol = e.target.value.trim().toUpperCase();
            applyFiltersToAll();
        });
        document.getElementById('clear-filters-btn').addEventListener('click', () => {
            filters.style = '';
            filters.strength = '';
            filters.symbol = '';
            document.getElementById('filter-style').value = '';
            document.getElementById('filter-strength').value = '';
            document.getElementById('filter-symbol').value = '';
            applyFiltersToAll();
        });
    }

    function applyFiltersToAll() {
        const feed = document.getElementById('scanner-feed');
        const cards = feed.querySelectorAll('.signal-card');
        cards.forEach(applyFiltersToCard);
    }

    function applyFiltersToCard(card) {
        const matchStyle = !filters.style || card.dataset.style === filters.style;
        const matchStrength = !filters.strength || card.dataset.strength === filters.strength;
        const matchSymbol = !filters.symbol || card.dataset.symbol.includes(filters.symbol);
        card.style.display = (matchStyle && matchStrength && matchSymbol) ? '' : 'none';
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    function updateLastSeenId(id) {
        if (id != null) lastSeenId = id;
    }

    function incrementSignalsToday() {
        signalCount++;
        document.getElementById('signals-today').textContent = signalCount;
        const now = new Date();
        document.getElementById('last-update').textContent =
            'Updated ' + now.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    }

    function updateSignalsCount(count) {
        signalCount = count;
        document.getElementById('signals-today').textContent = count;
    }

    function formatPrice(value) {
        return parseFloat(value).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    function formatSignalType(type) {
        const map = {
            STRONG_BUY: 'Strong Buy',
            BUY: 'Buy',
            SELL: 'Sell',
            STRONG_SELL: 'Strong Sell'
        };
        return map[type] || type;
    }

    function formatTime(isoString) {
        try {
            return new Date(isoString).toLocaleTimeString('en-IN', {
                hour: '2-digit', minute: '2-digit', second: '2-digit'
            });
        } catch {
            return '';
        }
    }

    function escapeHtml(str) {
        if (str == null) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    return { init };
})();
