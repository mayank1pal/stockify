// Copilot page logic for StockMan
// Note: innerHTML is used for rendering structured API responses only (same pattern as app.js and analysis.html).
// User input is rendered with textContent to prevent XSS.

let conversationHistory = [];
let holdings = [];

function setActiveNav() {
    document.querySelectorAll('.nav-link').forEach(function (link) {
        link.classList.remove('active');
        if (link.getAttribute('href') === '/copilot.html') {
            link.classList.add('active');
        }
    });
}

async function init() {
    var authStatus = await API.getAuthStatus();
    if (!authStatus.authenticated) {
        window.location.href = '/';
        return;
    }

    document.getElementById('user-name').textContent = authStatus.userName || 'Demo User';
    document.getElementById('user-initial').textContent = (authStatus.userName || 'D')[0].toUpperCase();
    document.getElementById('logout-btn').addEventListener('click', async function () {
        await API.logout();
        window.location.href = '/';
    });

    setActiveNav();
    await loadHoldings();

    // Handle Enter key (Shift+Enter for new line)
    document.getElementById('copilotInput').addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendQuery();
        }
    });
}

async function loadHoldings() {
    try {
        holdings = await API.getHoldings();
        var select = document.getElementById('symbolSelect');
        select.innerHTML = '<option value="">No specific stock</option>';
        holdings.forEach(function (h) {
            var option = document.createElement('option');
            option.value = h.tradingSymbol;
            option.textContent = h.tradingSymbol;
            select.appendChild(option);
        });
    } catch (error) {
        console.error('Error loading holdings:', error);
    }
}

async function sendQuery() {
    var input = document.getElementById('copilotInput');
    var query = input.value.trim();
    if (!query) return;

    var symbol = document.getElementById('symbolSelect').value;

    // Hide empty state and suggestions
    var emptyState = document.getElementById('emptyState');
    if (emptyState) {
        emptyState.style.display = 'none';
    }
    document.getElementById('suggestionChips').style.display = 'none';

    // Add user query to conversation history
    conversationHistory.push({ role: 'user', content: query });

    // Display user query using textContent (safe from XSS)
    var responseArea = document.getElementById('responseArea');
    var userDiv = document.createElement('div');
    userDiv.className = 'user-query-display';
    userDiv.textContent = query;
    responseArea.appendChild(userDiv);

    // Clear input
    input.value = '';

    // Show loading
    showLoading();

    try {
        var request = {
            query: query,
            conversationHistory: conversationHistory
        };
        if (symbol) {
            request.symbol = symbol;
        }

        var response = await API.copilotAsk(request);

        // Remove loading
        var loadingEl = document.getElementById('copilotLoading');
        if (loadingEl) loadingEl.remove();

        // Add assistant response to history
        conversationHistory.push({ role: 'assistant', content: response.summary || '' });

        // Render the response
        renderResponse(response);
    } catch (error) {
        var loadingEl2 = document.getElementById('copilotLoading');
        if (loadingEl2) loadingEl2.remove();
        showError('Failed to get response from Copilot. Please try again.');
    }
}

function renderResponse(response) {
    var responseArea = document.getElementById('responseArea');
    var resultDiv = document.createElement('div');
    resultDiv.className = 'copilot-result';

    // Meta badges
    var metaContainer = document.createElement('div');
    metaContainer.className = 'copilot-meta-badges';

    if (response.recommendation) {
        var recBadge = document.createElement('span');
        var recLower = response.recommendation.toLowerCase();
        var recClass = 'meta-badge recommendation';
        if (recLower.indexOf('sell') !== -1) {
            recClass += ' sell';
        } else if (recLower.indexOf('hold') !== -1) {
            recClass += ' hold';
        }
        recBadge.className = recClass;
        recBadge.textContent = response.recommendation;
        metaContainer.appendChild(recBadge);
    }

    if (response.confidence !== undefined && response.confidence !== null) {
        var confBadge = document.createElement('span');
        confBadge.className = 'meta-badge confidence';
        confBadge.textContent = 'Confidence: ' + Math.round(response.confidence * 100) + '%';
        metaContainer.appendChild(confBadge);
    }

    if (response.modelAgreement !== undefined && response.modelAgreement !== null) {
        var agrBadge = document.createElement('span');
        agrBadge.className = 'meta-badge agreement';
        agrBadge.textContent = 'Model Agreement: ' + Math.round(response.modelAgreement * 100) + '%';
        metaContainer.appendChild(agrBadge);
    }

    resultDiv.appendChild(metaContainer);

    // Summary — rendered from trusted API response (same pattern as analysis.html formatResponse)
    if (response.summary) {
        var summaryDiv = document.createElement('div');
        summaryDiv.className = 'copilot-summary';
        summaryDiv.innerHTML = formatMarkdown(response.summary);
        resultDiv.appendChild(summaryDiv);
    }

    // Trade plan card — all values rendered via textContent
    if (response.tradePlan) {
        var plan = response.tradePlan;
        var planCard = document.createElement('div');
        planCard.className = 'trade-plan-card';

        var planTitle = document.createElement('h4');
        planTitle.textContent = 'Trade Plan';
        planCard.appendChild(planTitle);

        var planGrid = document.createElement('div');
        planGrid.className = 'trade-plan-grid';

        var fields = [
            { label: 'Action', value: plan.action },
            { label: 'Entry Price', value: plan.entryPrice },
            { label: 'Target Price', value: plan.targetPrice },
            { label: 'Stop Loss', value: plan.stopLoss },
            { label: 'Position Size', value: plan.positionSize },
            { label: 'Time Horizon', value: plan.timeHorizon }
        ];

        fields.forEach(function (field) {
            if (field.value !== undefined && field.value !== null) {
                var item = document.createElement('div');
                item.className = 'trade-plan-item';

                var label = document.createElement('div');
                label.className = 'label';
                label.textContent = field.label;
                item.appendChild(label);

                var value = document.createElement('div');
                value.className = 'value';
                value.textContent = String(field.value);
                item.appendChild(value);

                planGrid.appendChild(item);
            }
        });

        planCard.appendChild(planGrid);
        resultDiv.appendChild(planCard);
    }

    // Reasoning chain (expandable agent cards)
    if (response.reasoningChain && response.reasoningChain.length > 0) {
        var chainDiv = document.createElement('div');
        chainDiv.className = 'reasoning-chain';

        var chainTitle = document.createElement('h4');
        chainTitle.textContent = 'Agent Reasoning';
        chainDiv.appendChild(chainTitle);

        response.reasoningChain.forEach(function (agent, index) {
            var card = document.createElement('div');
            card.className = 'agent-reasoning-card';

            var header = document.createElement('div');
            header.className = 'agent-reasoning-header';
            header.setAttribute('onclick', 'toggleAgent(' + index + ')');

            var infoDiv = document.createElement('div');
            infoDiv.className = 'agent-info';

            var iconDiv = document.createElement('div');
            iconDiv.className = 'agent-icon';
            iconDiv.textContent = agent.icon || agent.agentType.charAt(0);
            infoDiv.appendChild(iconDiv);

            var nameSpan = document.createElement('span');
            nameSpan.style.fontWeight = '600';
            nameSpan.textContent = agent.agentName || agent.agentType;
            infoDiv.appendChild(nameSpan);

            header.appendChild(infoDiv);

            var toggleSpan = document.createElement('span');
            toggleSpan.className = 'toggle-icon';
            toggleSpan.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>';
            header.appendChild(toggleSpan);

            card.appendChild(header);

            // Agent reasoning body — rendered from trusted API response
            var body = document.createElement('div');
            body.className = 'agent-reasoning-body';
            body.innerHTML = formatMarkdown(agent.finding || agent.reasoning || '');
            card.appendChild(body);

            chainDiv.appendChild(card);
        });

        resultDiv.appendChild(chainDiv);
    }

    // Dissenting views — rendered from trusted API response
    if (response.dissentingViews && response.dissentingViews.length > 0) {
        var dissentDiv = document.createElement('div');
        dissentDiv.className = 'dissenting-views';

        var dissentTitle = document.createElement('h4');
        dissentTitle.textContent = 'Dissenting Views';
        dissentDiv.appendChild(dissentTitle);

        response.dissentingViews.forEach(function (view) {
            var p = document.createElement('p');
            p.innerHTML = formatMarkdown(view);
            dissentDiv.appendChild(p);
        });

        resultDiv.appendChild(dissentDiv);
    }

    // Follow-up question chips — textContent used for chip text
    if (response.followUpQuestions && response.followUpQuestions.length > 0) {
        var followDiv = document.createElement('div');
        followDiv.className = 'followup-chips';

        response.followUpQuestions.forEach(function (q) {
            var chip = document.createElement('button');
            chip.className = 'followup-chip';
            chip.textContent = q;
            chip.onclick = function () { useSuggestion(chip); };
            followDiv.appendChild(chip);
        });

        resultDiv.appendChild(followDiv);
    }

    // Agents used / skipped info
    var infoLines = [];
    if (response.agentsUsed && response.agentsUsed.length > 0) {
        infoLines.push('Agents consulted: ' + response.agentsUsed.join(', '));
    }
    if (response.agentsSkipped && response.agentsSkipped.length > 0) {
        infoLines.push('Agents skipped: ' + response.agentsSkipped.join(', '));
    }
    if (infoLines.length > 0) {
        var agentInfoDiv = document.createElement('div');
        agentInfoDiv.className = 'agents-used-info';
        agentInfoDiv.textContent = infoLines.join(' | ');
        resultDiv.appendChild(agentInfoDiv);
    }

    // Divider for next conversation turn
    var divider = document.createElement('hr');
    divider.className = 'conversation-divider';
    resultDiv.appendChild(divider);

    responseArea.appendChild(resultDiv);

    // Scroll to result
    resultDiv.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function toggleAgent(index) {
    var cards = document.querySelectorAll('.agent-reasoning-card');
    if (cards[index]) {
        cards[index].classList.toggle('expanded');
    }
}

function useSuggestion(el) {
    var input = document.getElementById('copilotInput');
    input.value = el.textContent;
    sendQuery();
}

function showLoading() {
    var responseArea = document.getElementById('responseArea');
    var loadingDiv = document.createElement('div');
    loadingDiv.className = 'copilot-loading';
    loadingDiv.id = 'copilotLoading';

    var loader = document.createElement('div');
    loader.className = 'loader';
    for (var i = 0; i < 3; i++) {
        var dot = document.createElement('div');
        dot.className = 'loader-dot';
        loader.appendChild(dot);
    }
    loadingDiv.appendChild(loader);

    var loadingText = document.createElement('p');
    loadingText.style.color = 'var(--text-muted)';
    loadingText.textContent = 'Copilot is analyzing with multiple agents...';
    loadingDiv.appendChild(loadingText);

    responseArea.appendChild(loadingDiv);
    loadingDiv.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function showError(message) {
    var responseArea = document.getElementById('responseArea');
    var errorDiv = document.createElement('div');
    errorDiv.className = 'copilot-error';

    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('width', '48');
    svg.setAttribute('height', '48');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('fill', 'none');
    svg.setAttribute('stroke', 'var(--danger)');
    svg.setAttribute('stroke-width', '2');

    var circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    circle.setAttribute('cx', '12');
    circle.setAttribute('cy', '12');
    circle.setAttribute('r', '10');
    svg.appendChild(circle);

    var line1 = document.createElementNS('http://www.w3.org/2000/svg', 'line');
    line1.setAttribute('x1', '12');
    line1.setAttribute('y1', '8');
    line1.setAttribute('x2', '12');
    line1.setAttribute('y2', '12');
    svg.appendChild(line1);

    var line2 = document.createElementNS('http://www.w3.org/2000/svg', 'line');
    line2.setAttribute('x1', '12');
    line2.setAttribute('y1', '16');
    line2.setAttribute('x2', '12.01');
    line2.setAttribute('y2', '16');
    svg.appendChild(line2);

    errorDiv.appendChild(svg);

    var errMsg = document.createElement('p');
    errMsg.textContent = message;
    errorDiv.appendChild(errMsg);

    responseArea.appendChild(errorDiv);
}

// Format markdown from trusted API responses to HTML
// This follows the same pattern as analysis.html's formatMarkdown function
function formatMarkdown(text) {
    if (!text) return '';
    return text
        .replace(/### (.*?)(\n|$)/g, '<h4>$1</h4>')
        .replace(/## (.*?)(\n|$)/g, '<h3>$1</h3>')
        .replace(/# (.*?)(\n|$)/g, '<h2>$1</h2>')
        .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
        .replace(/\*(.*?)\*/g, '<em>$1</em>')
        .replace(/^\- (.*?)$/gm, '<li>$1</li>')
        .replace(/(<li>.*<\/li>)/s, '<ul>$1</ul>')
        .replace(/\n\n/g, '</p><p>')
        .replace(/\n/g, '<br>');
}

document.addEventListener('DOMContentLoaded', function () {
    init();
});
