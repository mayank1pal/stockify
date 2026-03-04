// API Service for StockMan
const API = {
    baseUrl: '',

    async getAuthStatus() {
        const response = await fetch(`${this.baseUrl}/api/auth/status`);
        return response.json();
    },

    async login() {
        const response = await fetch(`${this.baseUrl}/api/auth/login`);
        return response.json();
    },

    async logout() {
        await fetch(`${this.baseUrl}/api/auth/logout`, { method: 'POST' });
    },

    async enableDemoMode() {
        const response = await fetch(`${this.baseUrl}/api/auth/demo`, { method: 'POST' });
        return response.json();
    },

    async getPortfolioSummary() {
        const response = await fetch(`${this.baseUrl}/api/portfolio/summary`);
        return response.json();
    },

    async getHoldings() {
        const response = await fetch(`${this.baseUrl}/api/portfolio/holdings`);
        return response.json();
    },

    async getPositions() {
        const response = await fetch(`${this.baseUrl}/api/portfolio/positions`);
        return response.json();
    },

    async getHoldingBySymbol(symbol) {
        const response = await fetch(`${this.baseUrl}/api/portfolio/holding/${symbol}`);
        if (response.ok) {
            return response.json();
        }
        return null;
    },

    async analyzeStock(symbol, type = 'COMPREHENSIVE') {
        const response = await fetch(`${this.baseUrl}/api/analysis/stock/${symbol}?type=${type}`, {
            method: 'POST'
        });
        return response.json();
    },

    async analyzePortfolio() {
        const response = await fetch(`${this.baseUrl}/api/analysis/portfolio`, {
            method: 'POST'
        });
        return response.json();
    },

    async getLongTermStrategy(riskTolerance = 'MEDIUM', primaryGoal = 'WEALTH_CREATION') {
        const response = await fetch(`${this.baseUrl}/api/analysis/strategy/long-term`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ riskTolerance, primaryGoal })
        });
        return response.json();
    },

    async getShortTermStrategy() {
        const response = await fetch(`${this.baseUrl}/api/analysis/strategy/short-term`, {
            method: 'POST'
        });
        return response.json();
    },

    async getAgents() {
        const response = await fetch(`${this.baseUrl}/api/analysis/agents`);
        return response.json();
    },

    async customAnalysis(request) {
        const response = await fetch(`${this.baseUrl}/api/analysis/custom`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(request)
        });

        if (!response.ok) {
            throw new Error('Analysis request failed');
        }
        return response.json();
    }
};

// Utility functions
function formatCurrency(value) {
    return '₹' + parseFloat(value).toLocaleString('en-IN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
    });
}

function formatPercentage(value) {
    const num = parseFloat(value);
    const sign = num >= 0 ? '+' : '';
    return `${sign}${num.toFixed(2)}%`;
}

function getRatingClass(rating) {
    const ratingMap = {
        'STRONG_BUY': 'rating-strong-buy',
        'BUY': 'rating-buy',
        'HOLD': 'rating-hold',
        'SELL': 'rating-sell',
        'STRONG_SELL': 'rating-strong-sell'
    };
    return ratingMap[rating] || 'rating-hold';
}

function getRatingDisplay(rating) {
    const displayMap = {
        'STRONG_BUY': 'Strong Buy',
        'BUY': 'Buy',
        'HOLD': 'Hold',
        'SELL': 'Sell',
        'STRONG_SELL': 'Strong Sell'
    };
    return displayMap[rating] || rating;
}
