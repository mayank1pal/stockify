# Model Optimization Design: Per-Agent Model Selection via OpenRouter

**Date:** 2026-03-06
**Status:** Approved

## Problem

Current setup uses two separate LLM integrations (direct Gemini API + OpenRouter/DeepSeek) with a static one-model-per-provider assignment. No cost tracking, no per-agent model optimization, and the direct Gemini integration is redundant since BYOK Gemini is available through OpenRouter for free.

## Solution

Consolidate all LLM calls through OpenRouter as a single gateway. Assign the best model per agent based on real benchmarks and cost testing. Track per-call cost metadata for auditing.

## Agent-to-Model Mapping

| Agent | Model | Rationale | Cost/call |
|-------|-------|-----------|-----------|
| Fundamental | `google/gemini-2.5-flash` (BYOK) | Great structured output, free | $0.00 |
| Technical | `google/gemini-2.5-flash` (BYOK) | Speed + free | $0.00 |
| Quantitative | `qwen/qwen3-235b-a22b-2507` | Best math precision, $0.07/M | ~$0.0003 |
| Sentiment | `google/gemini-2.5-flash` (BYOK) | Narrative analysis, free | $0.00 |
| General | `google/gemini-2.5-flash` (BYOK) | Catch-all, free | $0.00 |
| Risk Assessor | `qwen/qwq-32b` | Deep reasoning (623 thinking tokens), $0.15/M | ~$0.0002 |
| Portfolio Optimizer | `google/gemini-2.5-flash` (BYOK) | Allocation math, free | $0.00 |
| Geopolitical | `qwen/qwen3-235b-a22b-2507` | Complex causal reasoning | ~$0.0003 |
| Trade Executor | `qwen/qwq-32b` | Precise entry/exit with reasoning | ~$0.0002 |
| Synthesizer | `qwen/qwen3-235b-a22b-2507` | Best at combining findings | ~$0.0003 |

**Estimated cost per full query (5 agents + synthesizer): ~$0.001**
**$10 budget = ~10,000 queries**

Fallback: if any paid model fails, fall back to BYOK Gemini 2.5 Flash (always available).

## Architecture Changes

### Remove
- `GeminiConfig.java` — no longer needed
- `GeminiService.java` — replaced by OpenRouterModelService
- `ClaudeModelService.java` — merged into OpenRouterModelService
- `AnthropicConfig.java` — replaced by OpenRouterConfig
- Direct Gemini WebClient bean, geminiApiKey bean

### Add
- `OpenRouterConfig.java` — single WebClient with `Authorization: Bearer` header
- `OpenRouterModelService.java` — implements AIModelService, takes model ID per call
- `LlmUsage.java` — per-call cost metadata DTO
- `CostMetadata.java` — aggregated cost DTO for response

### Modify
- `OrchestratorResponse.java` — add `costMetadata` field
- `AgentResult.java` — add `llmUsage` field
- `OrchestratorService.java` — per-agent model IDs, cost aggregation, single service
- `application.yml` — simplified to OpenRouter key + per-agent model config

## OpenRouter API Format

All calls go through OpenRouter's OpenAI-compatible endpoint:

```
POST https://openrouter.ai/api/v1/chat/completions
Authorization: Bearer {OPENROUTER_API_KEY}

Request: {
  "model": "google/gemini-2.5-flash",
  "max_tokens": 8192,
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "..."}
  ]
}

Response: {
  "id": "gen-...",
  "model": "google/gemini-2.5-flash",
  "choices": [{ "message": { "content": "..." } }],
  "usage": {
    "prompt_tokens": 74,
    "completion_tokens": 500,
    "cost": 0,
    "is_byok": true,
    "completion_tokens_details": { "reasoning_tokens": 0 },
    "cost_details": { "upstream_inference_cost": 0.0001349 }
  }
}
```

## Cost Metadata Schema

### Per-agent (LlmUsage)
```json
{
  "model": "qwen/qwq-32b",
  "generationId": "gen-...",
  "isByok": false,
  "promptTokens": 74,
  "completionTokens": 500,
  "reasoningTokens": 623,
  "cost": 0.0002111,
  "upstreamCost": 0.0002111,
  "latencyMs": 4200
}
```

### Aggregated (CostMetadata in OrchestratorResponse)
```json
{
  "totalCost": 0.0008,
  "totalPromptTokens": 412,
  "totalCompletionTokens": 2300,
  "totalReasoningTokens": 623,
  "byokCallCount": 4,
  "paidCallCount": 2,
  "agentCosts": [ ...per-agent LlmUsage entries... ]
}
```

### Async Audit
Generation IDs stored in each AgentResult allow querying OpenRouter's stats API:
```
GET https://openrouter.ai/api/v1/generation?id={generationId}
```
Returns: model, provider, tokens, cost, latency, cache_discount, is_byok, etc.

## Config Structure (application.yml)

```yaml
openrouter:
  api-key: ${OPENROUTER_API_KEY}
  base-url: https://openrouter.ai/api/v1
  default-model: google/gemini-2.5-flash
  fallback-model: google/gemini-2.5-flash
  max-tokens: 8192
  models:
    fundamental: google/gemini-2.5-flash
    technical: google/gemini-2.5-flash
    quantitative: qwen/qwen3-235b-a22b-2507
    sentiment: google/gemini-2.5-flash
    general: google/gemini-2.5-flash
    risk-assessor: qwen/qwq-32b
    portfolio-optimizer: google/gemini-2.5-flash
    geopolitical: qwen/qwen3-235b-a22b-2507
    trade-executor: qwen/qwq-32b
    synthesizer: qwen/qwen3-235b-a22b-2507
```

## Test Results (2026-03-06)

| Model | Task | Cost | BYOK | Tokens | Quality |
|-------|------|------|------|--------|---------|
| google/gemini-2.5-flash | Risk-reward calc | $0.000 | Yes | 57in/498out | Excellent |
| qwen/qwen3-235b-a22b-2507 | Risk-reward calc | $0.0003 | No | 67in/500out | Excellent |
| qwen/qwq-32b | Geopolitical risk | $0.0002 | No | 74in/500out+623 reasoning | Good |
| deepseek/deepseek-chat-v3-0324 | Risk analysis | $0.001 | No | ~1K in/1.5K out | Good |
| Free models (llama-3.3, gemma, glm) | Various | $0.000 | No | N/A | Unreliable (429s) |
