// Main Application Logic for StockMan

// Navigation state management
function setActiveNav() {
    const currentPage = window.location.pathname;
    document.querySelectorAll('.nav-link').forEach(link => {
        link.classList.remove('active');
        if (link.getAttribute('href') === currentPage) {
            link.classList.add('active');
        }
    });
}

// Mobile sidebar toggle
function toggleSidebar() {
    const sidebar = document.getElementById('sidebar');
    sidebar.classList.toggle('open');
}

// Show loading state
function showLoading(elementId) {
    const element = document.getElementById(elementId);
    if (element) {
        element.innerHTML = `
            <div style="text-align: center; padding: var(--space-xl);">
                <div class="loader">
                    <div class="loader-dot"></div>
                    <div class="loader-dot"></div>
                    <div class="loader-dot"></div>
                </div>
                <p style="margin-top: var(--space-md); color: var(--text-muted);">Loading...</p>
            </div>
        `;
    }
}

// Show error state
function showError(elementId, message) {
    const element = document.getElementById(elementId);
    if (element) {
        element.innerHTML = `
            <div style="text-align: center; padding: var(--space-xl);">
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--danger)" stroke-width="2" style="margin-bottom: var(--space-md);">
                    <circle cx="12" cy="12" r="10"/>
                    <line x1="12" y1="8" x2="12" y2="12"/>
                    <line x1="12" y1="16" x2="12.01" y2="16"/>
                </svg>
                <p style="color: var(--danger);">${message}</p>
            </div>
        `;
    }
}

// Render holdings table
function renderHoldingsTable(holdings, tableId) {
    const tbody = document.getElementById(tableId);
    if (!tbody) return;

    tbody.innerHTML = holdings.map(h => `
        <tr onclick="window.location.href='/analysis.html?symbol=${h.tradingSymbol}'" style="cursor: pointer;">
            <td>
                <div class="symbol-cell">
                    <div class="symbol-icon">${h.tradingSymbol.substring(0, 2)}</div>
                    <div>
                        <div class="symbol-name">${h.tradingSymbol}</div>
                        <div class="symbol-exchange">${h.exchange}</div>
                    </div>
                </div>
            </td>
            <td>${h.quantity}</td>
            <td>₹${parseFloat(h.averagePrice).toLocaleString('en-IN', { minimumFractionDigits: 2 })}</td>
            <td>₹${parseFloat(h.lastPrice).toLocaleString('en-IN', { minimumFractionDigits: 2 })}</td>
            <td>₹${parseFloat(h.investedValue).toLocaleString('en-IN', { minimumFractionDigits: 2 })}</td>
            <td>₹${parseFloat(h.currentValue).toLocaleString('en-IN', { minimumFractionDigits: 2 })}</td>
            <td style="color: ${h.pnl >= 0 ? 'var(--success)' : 'var(--danger)'}">
                ${h.pnl >= 0 ? '+' : ''}₹${parseFloat(h.pnl).toLocaleString('en-IN', { minimumFractionDigits: 2 })}
            </td>
            <td>
                <span class="stat-change ${h.pnlPercentage >= 0 ? 'positive' : 'negative'}">
                    ${h.pnlPercentage >= 0 ? '+' : ''}${parseFloat(h.pnlPercentage).toFixed(2)}%
                </span>
            </td>
            <td>
                <button class="btn btn-sm btn-primary" onclick="event.stopPropagation(); analyzeStock('${h.tradingSymbol}')">
                    Analyze
                </button>
            </td>
        </tr>
    `).join('');
}

// Render stock insight
function renderStockInsight(insight, containerId) {
    const container = document.getElementById(containerId);
    if (!container) return;

    container.innerHTML = `
        <div class="card" style="margin-bottom: var(--space-xl);">
            <div class="card-header">
                <div>
                    <h3 class="card-title">${insight.symbol}</h3>
                    <p style="color: var(--text-muted); font-size: 0.875rem;">
                        ${insight.analysisType} Analysis • ${new Date(insight.analysisDate).toLocaleDateString()}
                    </p>
                </div>
                <div class="rating ${getRatingClass(insight.overallRating)}">
                    ${getRatingDisplay(insight.overallRating)}
                </div>
            </div>
            
            <div style="margin-bottom: var(--space-lg);">
                <div style="display: flex; align-items: center; gap: var(--space-md); margin-bottom: var(--space-sm);">
                    <span style="color: var(--text-muted);">Confidence Score</span>
                    <span style="font-weight: 600;">${insight.confidenceScore}%</span>
                </div>
                <div class="progress-bar">
                    <div class="progress-fill" style="width: ${insight.confidenceScore}%"></div>
                </div>
            </div>
            
            <p style="font-size: 1rem; line-height: 1.7; margin-bottom: var(--space-lg);">
                ${insight.summary}
            </p>
        </div>

        <div class="analysis-grid">
            <div class="analysis-section">
                <h4>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M12 20V10"/>
                        <path d="M18 20V4"/>
                        <path d="M6 20v-4"/>
                    </svg>
                    Fundamental Analysis
                </h4>
                <p>${insight.fundamentalAnalysis || 'Not available'}</p>
            </div>
            
            <div class="analysis-section">
                <h4>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>
                    </svg>
                    Technical Analysis
                </h4>
                <p>${insight.technicalAnalysis || 'Not available'}</p>
            </div>
            
            <div class="analysis-section">
                <h4>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
                        <line x1="12" y1="9" x2="12" y2="13"/>
                        <line x1="12" y1="17" x2="12.01" y2="17"/>
                    </svg>
                    Risk Assessment
                </h4>
                <p>${insight.riskAssessment || 'Not available'}</p>
            </div>
            
            <div class="analysis-section">
                <h4>
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/>
                        <polyline points="17 6 23 6 23 12"/>
                    </svg>
                    Growth Potential
                </h4>
                <p>${insight.growthPotential || 'Not available'}</p>
            </div>
        </div>

        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-xl); margin-top: var(--space-xl);">
            <div class="card">
                <h4 style="color: var(--success); margin-bottom: var(--space-md);">Key Strengths</h4>
                <ul class="insight-list">
                    ${(insight.keyStrengths || []).map(s => `
                        <li>
                            <span class="insight-icon strength">✓</span>
                            <span>${s}</span>
                        </li>
                    `).join('')}
                </ul>
            </div>
            
            <div class="card">
                <h4 style="color: var(--danger); margin-bottom: var(--space-md);">Key Risks</h4>
                <ul class="insight-list">
                    ${(insight.keyRisks || []).map(r => `
                        <li>
                            <span class="insight-icon risk">!</span>
                            <span>${r}</span>
                        </li>
                    `).join('')}
                </ul>
            </div>
        </div>

        <div class="card" style="margin-top: var(--space-xl);">
            <h4 style="margin-bottom: var(--space-md);">Recommendations</h4>
            <ul class="insight-list">
                ${(insight.recommendations || []).map(r => `
                    <li>
                        <span class="insight-icon" style="background: var(--accent-glow); color: var(--accent-primary);">→</span>
                        <span>${r}</span>
                    </li>
                `).join('')}
            </ul>
        </div>

        <div class="card" style="margin-top: var(--space-xl);">
            <h4 style="margin-bottom: var(--space-lg);">Outlook</h4>
            <div style="display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-lg);">
                <div>
                    <div style="color: var(--text-muted); font-size: 0.875rem; margin-bottom: var(--space-xs);">Short-Term (1-3 months)</div>
                    <p>${insight.shortTermOutlook || 'N/A'}</p>
                </div>
                <div>
                    <div style="color: var(--text-muted); font-size: 0.875rem; margin-bottom: var(--space-xs);">Medium-Term (3-12 months)</div>
                    <p>${insight.mediumTermOutlook || 'N/A'}</p>
                </div>
                <div>
                    <div style="color: var(--text-muted); font-size: 0.875rem; margin-bottom: var(--space-xs);">Long-Term (1-5 years)</div>
                    <p>${insight.longTermOutlook || 'N/A'}</p>
                </div>
            </div>
        </div>
    `;
}

// Render investment strategy
function renderStrategy(strategy, containerId) {
    const container = document.getElementById(containerId);
    if (!container) return;

    container.innerHTML = `
        <div class="card" style="margin-bottom: var(--space-xl);">
            <div class="card-header">
                <div>
                    <h3 class="card-title">${strategy.strategyType.replace(/_/g, ' ')} Strategy</h3>
                    <p style="color: var(--text-muted); font-size: 0.875rem;">
                        Generated on ${new Date(strategy.generatedAt).toLocaleDateString()}
                    </p>
                </div>
                <div style="display: flex; gap: var(--space-md);">
                    <span class="badge ${strategy.riskLevel === 'LOW' ? 'badge-buy' : strategy.riskLevel === 'HIGH' ? 'badge-sell' : 'badge-hold'}">
                        ${strategy.riskLevel} Risk
                    </span>
                    <span class="rating rating-buy">Health: ${strategy.portfolioHealthScore}</span>
                </div>
            </div>
            
            <p style="font-size: 1rem; line-height: 1.7;">
                ${strategy.summary}
            </p>
        </div>

        <div style="display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-lg); margin-bottom: var(--space-xl);">
            <div class="card" style="border-left: 3px solid var(--success);">
                <h4 style="color: var(--success); margin-bottom: var(--space-md);">Stocks to Buy</h4>
                ${(strategy.stocksToBuy || []).length ?
            strategy.stocksToBuy.map(s => `<div class="badge badge-buy" style="margin-right: var(--space-sm); margin-bottom: var(--space-sm);">${s}</div>`).join('') :
            '<p style="color: var(--text-muted);">No recommendations</p>'
        }
            </div>
            <div class="card" style="border-left: 3px solid var(--warning);">
                <h4 style="color: var(--warning); margin-bottom: var(--space-md);">Stocks to Hold</h4>
                ${(strategy.stocksToHold || []).length ?
            strategy.stocksToHold.map(s => `<div class="badge badge-hold" style="margin-right: var(--space-sm); margin-bottom: var(--space-sm);">${s}</div>`).join('') :
            '<p style="color: var(--text-muted);">No recommendations</p>'
        }
            </div>
            <div class="card" style="border-left: 3px solid var(--danger);">
                <h4 style="color: var(--danger); margin-bottom: var(--space-md);">Stocks to Sell</h4>
                ${(strategy.stocksToSell || []).length ?
            strategy.stocksToSell.map(s => `<div class="badge badge-sell" style="margin-right: var(--space-sm); margin-bottom: var(--space-sm);">${s}</div>`).join('') :
            '<p style="color: var(--text-muted);">No recommendations</p>'
        }
            </div>
        </div>

        ${strategy.recommendations && strategy.recommendations.length ? `
            <div class="card" style="margin-bottom: var(--space-xl);">
                <h4 style="margin-bottom: var(--space-lg);">Strategic Recommendations</h4>
                <div style="display: flex; flex-direction: column; gap: var(--space-lg);">
                    ${strategy.recommendations.map(rec => `
                        <div style="padding: var(--space-lg); background: var(--bg-tertiary); border-radius: var(--radius-lg); border-left: 3px solid ${rec.priority === 'HIGH' ? 'var(--danger)' : rec.priority === 'MEDIUM' ? 'var(--warning)' : 'var(--success)'};">
                            <div style="display: flex; justify-content: space-between; align-items: start; margin-bottom: var(--space-sm);">
                                <h5 style="font-size: 1rem;">${rec.title}</h5>
                                <span class="badge ${rec.priority === 'HIGH' ? 'badge-sell' : rec.priority === 'MEDIUM' ? 'badge-hold' : 'badge-buy'}">${rec.priority}</span>
                            </div>
                            <p style="margin-bottom: var(--space-sm);">${rec.description}</p>
                            <div style="display: flex; gap: var(--space-lg); font-size: 0.875rem; color: var(--text-muted);">
                                <span>⏱ ${rec.timeframe}</span>
                                <span>📈 ${rec.expectedImpact}</span>
                            </div>
                        </div>
                    `).join('')}
                </div>
            </div>
        ` : ''}

        ${strategy.rebalanceActions && strategy.rebalanceActions.length ? `
            <div class="card" style="margin-bottom: var(--space-xl);">
                <h4 style="margin-bottom: var(--space-lg);">Rebalancing Actions</h4>
                <div class="table-container">
                    <table>
                        <thead>
                            <tr>
                                <th>Stock</th>
                                <th>Action</th>
                                <th>Current Weight</th>
                                <th>Target Weight</th>
                                <th>Reason</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${strategy.rebalanceActions.map(action => `
                                <tr>
                                    <td><strong>${action.symbol}</strong></td>
                                    <td>
                                        <span class="badge ${action.action === 'BUY' || action.action === 'INCREASE' ? 'badge-buy' : 'badge-sell'}">
                                            ${action.action}
                                        </span>
                                    </td>
                                    <td>${action.currentWeight}</td>
                                    <td>${action.targetWeight}</td>
                                    <td>${action.reason}</td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            </div>
        ` : ''}

        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-xl);">
            <div class="card">
                <h4 style="margin-bottom: var(--space-md);">Risk Mitigation</h4>
                <ul class="insight-list">
                    ${(strategy.riskMitigationSteps || []).map(step => `
                        <li>
                            <span class="insight-icon" style="background: var(--warning-bg); color: var(--warning);">⚠</span>
                            <span>${step}</span>
                        </li>
                    `).join('')}
                </ul>
            </div>
            
            <div class="card">
                <h4 style="margin-bottom: var(--space-md);">Opportunities</h4>
                <div style="margin-bottom: var(--space-md);">
                    <div style="color: var(--text-muted); font-size: 0.875rem; margin-bottom: var(--space-xs);">Sector Opportunities</div>
                    ${(strategy.sectorOpportunities || []).map(s => `<span class="badge badge-buy" style="margin-right: var(--space-sm);">${s}</span>`).join('')}
                </div>
                <div>
                    <div style="color: var(--text-muted); font-size: 0.875rem; margin-bottom: var(--space-xs);">Emerging Trends</div>
                    ${(strategy.emergingTrends || []).map(t => `<span class="badge badge-hold" style="margin-right: var(--space-sm);">${t}</span>`).join('')}
                </div>
            </div>
        </div>
    `;
}

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    setActiveNav();
});
