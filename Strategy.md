# PropAMM — Prop Trading Platform
## Full Build Specification & Execution Strategy

> **Stack**: Next.js · Supabase · Bybit API · Solana (fee collection) · Node.js keeper bot  
> **Model**: Users pay $100 entry fee → receive $2,000 funded account → auto-liquidated if equity falls below $1,910  
> **Purpose**: This document is the complete source of truth for building PropAMM. Hand this to your AI coding agent as the primary brief.

---

## Table of Contents

1. [Economic Model & Unit Economics](#1-economic-model)
2. [System Architecture](#2-system-architecture)
3. [Bill of Materials](#3-bill-of-materials)
4. [Database Schema](#4-database-schema)
5. [Bybit API Deep Dive](#5-bybit-api-deep-dive)
6. [Core Module Specifications](#6-core-module-specifications)
7. [Risk Engine Design](#7-risk-engine-design)
8. [Build Phases & Task Breakdown](#8-build-phases)
9. [Environment Variables & Config](#9-environment-variables)
10. [Legal & Compliance Checklist](#10-legal--compliance)
11. [Go-Live Checklist](#11-go-live-checklist)

---

## 1. Economic Model

### How Money Flows

```
User pays $100 (USDT, on-chain)
        ↓
Platform verifies payment on Solana
        ↓
Platform creates Bybit sub-account
Platform transfers $2,000 USDT from master treasury → sub-account
        ↓
User trades via PropAMM UI (proxied through platform's Bybit sub-account API keys)
        ↓
Risk engine monitors equity in real-time
        ↓
[Path A] Equity < $1,910 → auto-liquidate all positions
         → transfer remaining balance back to master treasury
         → $100 entry fee is consumed (platform revenue)
         → net loss to platform: max $90 per blown account

[Path B] User hits profit target or requests withdrawal
         → Platform closes positions / user exits manually
         → Profit split: 80% trader / 20% platform (configurable)
         → Trader's share paid out in USDT to their Solana wallet
         → $2,000 principal returns to master treasury
```

### Unit Economics Per 100 Users (Conservative)

| Metric | Value |
|--------|-------|
| Entry fees collected | $10,000 |
| Capital deployed ($2,000 × 100) | $200,000 |
| Assumed blow-up rate | 90% |
| Capital recovered from blown accounts | ~$181,000 (avg $1,955 left) |
| Net capital at risk | $19,000 (10 profitable traders × $1,900 avg drawdown) |
| Platform fee revenue (entries) | $9,000 (90 blown × $100) |
| Profit share from winners (assume avg $500 profit × 20%) | $1,000 |
| **Total platform revenue per 100 users** | **~$10,000** |
| **Treasury capital consumed** | **~$0 to $9,000 worst case** |

### Treasury Yield (Bonus Revenue)
- Idle treasury funds (not currently in sub-accounts) can be deployed in Bybit Earn (yield product)
- Even 5% APY on $500K treasury = $25,000/yr passive
- This is risk-free revenue layer on top of prop firm fees

### Drawdown Math
- Funded amount: $2,000
- Drawdown buffer: $90 (4.5%)
- Floor equity: $1,910
- Why $90 buffer maps to $100 fee: the $10 gap is the platform's minimum margin of safety
- **Recommended: increase buffer to $100 exactly** (floor = $1,900) for cleaner UX and math

---

## 2. System Architecture

### Component Map

```
┌─────────────────────────────────────────────────────────────────┐
│                        FRONTEND (Next.js)                       │
│  /onboard  /dashboard  /trade  /leaderboard  /withdraw          │
└────────────────────┬────────────────────────────────────────────┘
                     │ REST / Realtime (Supabase client)
                     │ WebSocket (Supabase Realtime channels)
┌────────────────────▼─────────────────────────────────────────────┐
│                    SUPABASE (Backend)                            │
│  PostgreSQL DB · Auth · Storage · Edge Functions · Realtime      │
│                                                                  │
│  Edge Functions:                                                 │
│  - /verify-deposit      (Solana tx verification)                 │
│  - /create-funded-account (Bybit sub-account provisioning)       │
│  - /proxy-order         (forward orders to Bybit)                │
│  - /request-withdrawal  (calculate payout, initiate transfer)    │
└──────────┬───────────────────────────────┬───────────────────────┘
           │                               │
           │ Bybit REST API                │ Solana RPC
┌──────────▼──────────┐        ┌───────────▼──────────────┐
│    BYBIT (Execution) │       │  SOLANA (Fee Collection) │
│                      │       │                          │
│  Master Account      │       │  Fee collection wallet   │
│  ├─ Sub-account 001  │       │  (watch for $100 USDT    │
│  ├─ Sub-account 002  │       │  deposits from users)    │
│  └─ Sub-account N    │       └──────────────────────────┘
│                      │
│  WebSocket streams:  │
│  Position updates    │
│  Wallet equity       │
└─────────┬────────────┘
          │ WebSocket subscriptions
┌─────────▼─────────────────────────────────────────────────┐
│               RISK ENGINE (Keeper Bot)                    │
│  Node.js process — runs on Railway / Fly.io               │
│  Subscribes to all active sub-account WebSocket feeds     │
│  Checks equity every tick against $1,910 floor            │
│  Triggers close-all-positions + transfer-back on breach   │
└───────────────────────────────────────────────────────────┘
```

### Data Flow: New User Onboarding

```
1. User connects Solana wallet (Phantom/Backpack)
2. User clicks "Get Funded Account" → sees $100 payment prompt
3. User approves USDT SPL transfer to platform wallet
4. Frontend calls /api/verify-deposit with {txSignature, userWalletAddress}
5. Edge Function verifies tx on-chain (Helius RPC):
   - Confirms recipient = platform wallet
   - Confirms amount >= 100 USDT
   - Confirms tx is finalized (not just confirmed)
6. On success: creates funded_accounts row in Supabase (status: provisioning)
7. Edge Function calls Bybit API:
   a. POST /v5/user/create-sub-member → creates sub-account
   b. POST /v5/user/create-sub-api → creates API key pair for sub-account
   c. POST /v5/asset/transfer/inter-transfer → sends $2,000 USDT from master → sub
8. Supabase row updated: status → active, bybit_sub_uid, encrypted API keys stored
9. User sees their funded dashboard
```

### Data Flow: Trade Execution

```
1. User submits order from frontend (symbol, side, qty, type, price)
2. Frontend calls /api/proxy-order (authenticated, server-side only)
3. Edge Function:
   - Loads user's sub-account API keys (decrypt from DB)
   - Validates order doesn't exceed position limits
   - Forwards order to Bybit: POST /v5/order/create
   - Stores order record in Supabase
4. Bybit confirms order → response returned to user
5. Risk engine WebSocket receives position update → re-evaluates equity
```

### Data Flow: Risk Engine Liquidation

```
1. Bybit WebSocket pushes wallet update: equity = $1,908
2. Risk engine: equity ($1,908) < floor ($1,910) → BREACH
3. Risk engine immediately:
   a. Fetches all open positions for sub-account
   b. Places market close orders for each position (POST /v5/order/create, reduceOnly: true)
   c. Cancels all open orders (POST /v5/order/cancel-all)
   d. Waits for positions to fully close (poll with backoff)
   e. Transfers remaining balance back to master (POST /v5/asset/transfer/inter-transfer)
4. Updates Supabase: funded_accounts.status → blown, blown_at timestamp
5. Sends notification to user (email / in-app)
6. Marks $100 entry fee as consumed in fee_ledger table
```

---

## 3. Bill of Materials

### 3.1 External APIs

| Service | Purpose | Cost | Notes |
|---------|---------|------|-------|
| **Bybit API** | Trade execution, sub-accounts, real-time data | Free (pay spread/fees) | Need verified business account for sub-account creation |
| **Helius RPC** | Solana tx verification, USDT tracking | Free tier: 1M credits/mo | Upgrade to Growth ($49/mo) at scale |
| **Supabase** | DB, auth, edge functions, realtime | Free tier → Pro $25/mo | Pro needed for Edge Functions + Realtime at scale |
| **Redis (Upstash)** | Risk engine state cache, rate limiting | Free tier → $0.2/100K commands | Optional but recommended for risk engine |
| **Resend** | Transactional email (blowup notices, withdrawals) | Free 3K emails/mo | Or use Supabase built-in |
| **Vercel** | Frontend hosting | Free → Pro $20/mo | Or deploy on Railway alongside the keeper bot |

### 3.2 NPM Packages — Frontend (Next.js)

```json
{
  "dependencies": {
    "bybit-api": "^3.x",
    "@solana/web3.js": "^1.x",
    "@solana/spl-token": "^0.x",
    "@supabase/supabase-js": "^2.x",
    "@reown/appkit": "^1.x",
    "@reown/appkit-adapter-solana": "^1.x",
    "lightweight-charts": "^4.x",
    "swr": "^2.x",
    "zustand": "^4.x",
    "recharts": "^2.x",
    "date-fns": "^3.x",
    "zod": "^3.x"
  }
}
```

### 3.3 NPM Packages — Risk Engine (Node.js Keeper)

```json
{
  "dependencies": {
    "bybit-api": "^3.x",
    "@supabase/supabase-js": "^2.x",
    "ioredis": "^5.x",
    "winston": "^3.x",
    "node-cron": "^3.x",
    "dotenv": "^16.x",
    "axios": "^1.x"
  }
}
```

### 3.4 Infrastructure

| Component | Service | Spec | Cost |
|-----------|---------|------|------|
| Frontend | Vercel | Hobby → Pro | $0–$20/mo |
| Database | Supabase | Pro plan | $25/mo |
| Risk Engine / Keeper | Railway | Starter | $5–$20/mo |
| Redis cache | Upstash | Pay-as-you-go | $0–$10/mo |
| Monitoring | Better Stack (Logtail) | Free tier | $0 |
| Secrets mgmt | Vercel + Railway env vars | — | Free |

**Total infrastructure burn rate: ~$50–$80/mo to start**

---

## 4. Database Schema

```sql
-- ─────────────────────────────────────
-- USERS (extends Supabase auth.users)
-- ─────────────────────────────────────
CREATE TABLE public.profiles (
  id              UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  wallet_address  TEXT UNIQUE NOT NULL,        -- Solana wallet
  username        TEXT UNIQUE,
  email           TEXT,
  kyc_status      TEXT DEFAULT 'none',         -- none | pending | approved | rejected
  created_at      TIMESTAMPTZ DEFAULT NOW(),
  updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- ─────────────────────────────────────
-- FUNDED ACCOUNTS
-- ─────────────────────────────────────
CREATE TABLE public.funded_accounts (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             UUID NOT NULL REFERENCES public.profiles(id),
  
  -- Status lifecycle: provisioning → active → blown | closed | withdrawn
  status              TEXT NOT NULL DEFAULT 'provisioning',
  
  -- Financial parameters
  entry_fee_usd       NUMERIC(10,2) NOT NULL DEFAULT 100.00,
  funded_amount_usd   NUMERIC(10,2) NOT NULL DEFAULT 2000.00,
  floor_equity_usd    NUMERIC(10,2) NOT NULL DEFAULT 1900.00,  -- liquidation trigger
  max_drawdown_pct    NUMERIC(5,2) NOT NULL DEFAULT 5.00,      -- 5%
  profit_share_pct    NUMERIC(5,2) NOT NULL DEFAULT 80.00,     -- trader's cut
  
  -- Bybit sub-account (SENSITIVE — encrypted at rest)
  bybit_sub_uid       TEXT,                    -- Bybit sub-account UID
  bybit_api_key_enc   TEXT,                    -- AES-256 encrypted API key
  bybit_api_secret_enc TEXT,                   -- AES-256 encrypted API secret
  
  -- Live metrics (updated by risk engine)
  current_equity_usd  NUMERIC(10,2),
  peak_equity_usd     NUMERIC(10,2),
  realized_pnl_usd    NUMERIC(10,2) DEFAULT 0,
  unrealized_pnl_usd  NUMERIC(10,2) DEFAULT 0,
  last_equity_sync    TIMESTAMPTZ,
  
  -- Lifecycle timestamps
  provisioned_at      TIMESTAMPTZ,
  activated_at        TIMESTAMPTZ,
  blown_at            TIMESTAMPTZ,
  closed_at           TIMESTAMPTZ,
  
  created_at          TIMESTAMPTZ DEFAULT NOW(),
  updated_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_funded_accounts_user_id ON public.funded_accounts(user_id);
CREATE INDEX idx_funded_accounts_status ON public.funded_accounts(status);
CREATE INDEX idx_funded_accounts_bybit_uid ON public.funded_accounts(bybit_sub_uid);

-- ─────────────────────────────────────
-- DEPOSIT TRANSACTIONS (Solana fee collection)
-- ─────────────────────────────────────
CREATE TABLE public.deposit_transactions (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             UUID NOT NULL REFERENCES public.profiles(id),
  funded_account_id   UUID REFERENCES public.funded_accounts(id),
  
  tx_signature        TEXT UNIQUE NOT NULL,    -- Solana tx signature
  from_wallet         TEXT NOT NULL,
  to_wallet           TEXT NOT NULL,           -- platform fee wallet
  amount_usd          NUMERIC(10,2) NOT NULL,
  token_mint          TEXT NOT NULL,           -- USDT SPL mint address
  
  status              TEXT DEFAULT 'pending',  -- pending | confirmed | failed
  verified_at         TIMESTAMPTZ,
  
  created_at          TIMESTAMPTZ DEFAULT NOW()
);

-- ─────────────────────────────────────
-- ORDERS (mirror of Bybit orders)
-- ─────────────────────────────────────
CREATE TABLE public.orders (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  funded_account_id   UUID NOT NULL REFERENCES public.funded_accounts(id),
  
  bybit_order_id      TEXT UNIQUE,
  symbol              TEXT NOT NULL,           -- e.g. BTCUSDT
  side                TEXT NOT NULL,           -- Buy | Sell
  order_type          TEXT NOT NULL,           -- Market | Limit
  qty                 NUMERIC(20,8) NOT NULL,
  price               NUMERIC(20,8),           -- null for market
  reduce_only         BOOLEAN DEFAULT FALSE,
  
  status              TEXT,                    -- New | Filled | Cancelled | PartiallyFilled
  avg_fill_price      NUMERIC(20,8),
  filled_qty          NUMERIC(20,8),
  fee_usd             NUMERIC(10,6),
  
  -- Risk engine flag
  is_liquidation_order BOOLEAN DEFAULT FALSE,
  
  bybit_created_at    TIMESTAMPTZ,
  bybit_updated_at    TIMESTAMPTZ,
  created_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_orders_funded_account ON public.orders(funded_account_id);
CREATE INDEX idx_orders_bybit_id ON public.orders(bybit_order_id);

-- ─────────────────────────────────────
-- EQUITY SNAPSHOTS (risk engine audit trail)
-- ─────────────────────────────────────
CREATE TABLE public.equity_snapshots (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  funded_account_id   UUID NOT NULL REFERENCES public.funded_accounts(id),
  equity_usd          NUMERIC(10,2) NOT NULL,
  unrealized_pnl_usd  NUMERIC(10,2),
  available_balance   NUMERIC(10,2),
  snapshot_source     TEXT,                    -- websocket | poll | liquidation_check
  created_at          TIMESTAMPTZ DEFAULT NOW()
);

-- Partition this table by month in production — it will grow very fast
CREATE INDEX idx_equity_snapshots_account_time 
  ON public.equity_snapshots(funded_account_id, created_at DESC);

-- ─────────────────────────────────────
-- WITHDRAWALS
-- ─────────────────────────────────────
CREATE TABLE public.withdrawals (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  funded_account_id   UUID NOT NULL REFERENCES public.funded_accounts(id),
  user_id             UUID NOT NULL REFERENCES public.profiles(id),
  
  gross_profit_usd    NUMERIC(10,2) NOT NULL,  -- total profit above $2,000
  trader_share_usd    NUMERIC(10,2) NOT NULL,  -- gross_profit * 0.80
  platform_fee_usd    NUMERIC(10,2) NOT NULL,  -- gross_profit * 0.20
  
  destination_wallet  TEXT NOT NULL,           -- user's Solana wallet
  
  status              TEXT DEFAULT 'pending',  -- pending | processing | completed | failed
  bybit_transfer_id   TEXT,
  solana_tx_signature TEXT,
  
  requested_at        TIMESTAMPTZ DEFAULT NOW(),
  completed_at        TIMESTAMPTZ
);

-- ─────────────────────────────────────
-- FEE LEDGER (platform revenue tracking)
-- ─────────────────────────────────────
CREATE TABLE public.fee_ledger (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  funded_account_id   UUID NOT NULL REFERENCES public.funded_accounts(id),
  
  fee_type            TEXT NOT NULL,           -- entry_fee | profit_share | penalty
  amount_usd          NUMERIC(10,2) NOT NULL,
  description         TEXT,
  
  created_at          TIMESTAMPTZ DEFAULT NOW()
);

-- ─────────────────────────────────────
-- RISK ENGINE EVENTS (audit log)
-- ─────────────────────────────────────
CREATE TABLE public.risk_events (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  funded_account_id   UUID NOT NULL REFERENCES public.funded_accounts(id),
  
  event_type          TEXT NOT NULL,           -- equity_warning | breach_detected | liquidation_initiated | liquidation_complete
  equity_at_event     NUMERIC(10,2),
  floor_at_event      NUMERIC(10,2),
  message             TEXT,
  metadata            JSONB,
  
  created_at          TIMESTAMPTZ DEFAULT NOW()
);

-- ─────────────────────────────────────
-- ROW-LEVEL SECURITY POLICIES
-- ─────────────────────────────────────
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.funded_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.withdrawals ENABLE ROW LEVEL SECURITY;

-- Users can only see their own data
CREATE POLICY "users_own_profile" ON public.profiles
  FOR ALL USING (auth.uid() = id);

CREATE POLICY "users_own_funded_accounts" ON public.funded_accounts
  FOR SELECT USING (auth.uid() = user_id);

-- IMPORTANT: Never expose bybit API keys to client — use server-side only queries
-- Add bybit_api_key_enc and bybit_api_secret_enc to a separate table
-- with NO RLS select for users, only accessible by service_role
CREATE TABLE public.funded_account_secrets (
  funded_account_id   UUID PRIMARY KEY REFERENCES public.funded_accounts(id),
  bybit_api_key_enc   TEXT NOT NULL,
  bybit_api_secret_enc TEXT NOT NULL,
  encryption_key_id   TEXT NOT NULL,           -- reference to KMS key version
  created_at          TIMESTAMPTZ DEFAULT NOW()
);
-- NO RLS policy for user select — service_role only
```

---

## 5. Bybit API Deep Dive

### 5.1 Account Setup Requirements

Before building, complete these steps on Bybit:

1. **Create a verified business account** at bybit.com (individual may work initially)
2. **Enable sub-account creation** — requires identity verification, may need to contact Bybit support
3. **Create a master account API key** with these permissions:
   - `Read` — wallet balance, positions
   - `Trade` — place/cancel orders
   - `Transfer` — inter-account transfers
   - `Sub-account management` — create sub-accounts
4. **Store master API key in Railway/Vercel environment variables only**

### 5.2 Key Bybit API Endpoints

#### Sub-Account Management

```typescript
// Create a sub-account
POST /v5/user/create-sub-member
Body: {
  username: string,        // e.g. "prop_user_${uuid.slice(0,8)}"
  password: string,        // generate random 16-char password
  memberType: 1,           // 1 = normal sub-account
  switch: 1,               // 1 = enable quick login
  note: string             // internal reference e.g. funded_account_id
}
Response: { uid: string, username: string }

// Create API key for sub-account (call this after creating sub-account)
POST /v5/user/create-sub-api
Body: {
  subuid: string,          // uid from above
  note: string,
  readOnly: 0,             // 0 = read-write
  permissions: {
    ContractTrade: ["Order", "Position"],
    Wallet: ["AccountTransfer"],
    Spot: ["SpotTrade"]
  }
}
Response: { apikey: string, secret: string }
// → Encrypt and store in funded_account_secrets table
```

#### Fund Transfer (Master ↔ Sub)

```typescript
// Transfer funds from master to sub-account
POST /v5/asset/transfer/inter-transfer
Body: {
  transferId: string,      // UUID you generate (idempotency key)
  coin: "USDT",
  amount: "2000",
  fromAccountType: "UNIFIED",
  toAccountType: "UNIFIED",
  fromMemberId: masterUid,
  toMemberId: subUid
}

// Transfer funds back from sub to master (after liquidation)
// Same endpoint, swap fromMemberId and toMemberId
```

#### Order Management (using sub-account API keys)

```typescript
// Place order
POST /v5/order/create
Headers: { apikey: subApiKey, sign: computeHmac(subApiSecret) }
Body: {
  category: "linear",      // perpetual futures (USDT-margined)
  symbol: "BTCUSDT",
  side: "Buy" | "Sell",
  orderType: "Market" | "Limit",
  qty: "0.001",
  price: "45000",          // only for Limit
  timeInForce: "GTC",
  reduceOnly: false,
  closeOnTrigger: false
}

// Cancel all open orders (for liquidation)
POST /v5/order/cancel-all
Body: { category: "linear", symbol: "" }  // empty symbol = all symbols

// Close all positions (for liquidation) — place reduce-only market orders
POST /v5/order/create
Body: {
  category: "linear",
  symbol: position.symbol,
  side: position.side === "Buy" ? "Sell" : "Buy",  // opposite side
  orderType: "Market",
  qty: position.size,
  reduceOnly: true,
  closeOnTrigger: true
}
```

#### Account State

```typescript
// Get wallet balance (equity)
GET /v5/account/wallet-balance?accountType=UNIFIED
Response: {
  result: {
    list: [{
      totalEquity: "1985.23",         // ← THIS is what we monitor
      totalWalletBalance: "1990.00",
      totalUnrealisedPnl: "-4.77",
      totalAvailableBalance: "1880.00"
    }]
  }
}

// Get positions
GET /v5/position/list?category=linear&settleCoin=USDT
```

### 5.3 WebSocket — Real-Time Risk Monitoring

```typescript
// Each sub-account needs its own WebSocket connection
// Using bybit-api npm package:

import { WebsocketClient } from 'bybit-api';

const ws = new WebsocketClient({
  key: subAccountApiKey,
  secret: subAccountApiSecret,
  market: 'v5',
  testnet: false
});

// Subscribe to wallet updates (equity changes on every trade/fill)
ws.subscribeV5('wallet', 'linear');

ws.on('update', (data) => {
  if (data.topic === 'wallet') {
    const equity = parseFloat(data.data[0].totalEquity);
    checkDrawdownBreach(fundedAccountId, equity);
  }
});

// Subscribe to position updates
ws.subscribeV5('position', 'linear');
ws.on('update', (data) => {
  if (data.topic === 'position') {
    updatePositionCache(fundedAccountId, data.data);
  }
});
```

### 5.4 Authentication (HMAC Signing)

```typescript
// All Bybit API calls require HMAC-SHA256 signature
// The bybit-api npm package handles this automatically
// For manual implementation:

import crypto from 'crypto';

function signRequest(apiSecret: string, timestamp: number, params: object): string {
  const paramStr = JSON.stringify(params);
  const signStr = `${timestamp}${apiKey}${recvWindow}${paramStr}`;
  return crypto
    .createHmac('sha256', apiSecret)
    .update(signStr)
    .digest('hex');
}
```

---

## 6. Core Module Specifications

### Module 1: Onboarding Flow (`/onboard`)

**Pages & Components:**
- `OnboardPage` — marketing copy, "Get Funded" CTA
- `ConnectWalletStep` — Reown AppKit wallet connection
- `PaymentStep` — shows $100 USDT payment prompt, triggers wallet tx
- `ProvisioningStep` — loading state while Bybit sub-account is created
- `DashboardRedirect` — success state, link to funded dashboard

**API Routes (Next.js `/app/api/`):**

```
POST /api/onboard/verify-deposit
  Body: { txSignature: string, walletAddress: string }
  1. Verify tx on Solana (Helius RPC)
  2. Confirm amount, recipient, token mint
  3. Create funded_accounts row
  4. Trigger provision-account background job
  Returns: { fundedAccountId: string, status: 'provisioning' }

POST /api/onboard/provision-account  [INTERNAL — called by verify-deposit]
  1. Call Bybit: create sub-account
  2. Call Bybit: create sub-account API keys
  3. Encrypt API keys with AES-256-GCM
  4. Store in funded_account_secrets
  5. Call Bybit: transfer $2,000 USDT master → sub
  6. Update funded_accounts status → active
  7. Register sub-account WebSocket with risk engine
  Returns: void
```

**Solana USDT Verification Logic:**

```typescript
import { Connection, ParsedTransactionWithMeta } from '@solana/web3.js';

const USDT_MINT = 'Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB'; // USDT SPL
const PLATFORM_WALLET = process.env.PLATFORM_FEE_WALLET;
const MIN_AMOUNT = 100 * 1_000_000; // 100 USDT (6 decimals)

async function verifyDepositTx(
  connection: Connection, 
  txSignature: string,
  expectedFrom: string
): Promise<boolean> {
  const tx = await connection.getParsedTransaction(txSignature, {
    commitment: 'finalized',     // must be finalized, not just confirmed
    maxSupportedTransactionVersion: 0
  });
  
  if (!tx || tx.meta?.err) return false;
  
  // Find SPL token transfer instruction
  const transfers = tx.transaction.message.instructions
    .filter(ix => 'parsed' in ix && ix.parsed?.type === 'transferChecked');
  
  const validTransfer = transfers.find(ix => {
    const info = (ix as any).parsed.info;
    return (
      info.mint === USDT_MINT &&
      info.authority === expectedFrom &&
      info.destination === PLATFORM_WALLET &&
      parseInt(info.tokenAmount.amount) >= MIN_AMOUNT
    );
  });
  
  return !!validTransfer;
}
```

---

### Module 2: Trading Dashboard (`/dashboard`)

**Layout:**
- Left panel: account summary card (equity, PnL, drawdown meter)
- Center: TradingView Lightweight Charts price chart
- Right panel: order book / recent trades
- Bottom: open positions table, order history

**Key Components:**

```
<FundedAccountSummary />
  - Current equity: $X,XXX.XX
  - P&L today: +/- $XX.XX
  - Drawdown meter: visual bar showing distance from floor
  - Status badge: ACTIVE / WARNING / BLOWN

<DrawdownMeter equity={equity} floor={1900} funded={2000} />
  - Progress bar: green → yellow (at 3%) → red (at 4%)
  - Shows: "$XX.XX remaining before liquidation"

<TradingChart symbol={selectedSymbol} />
  - Lightweight Charts (TradingView)
  - Candlestick + volume
  - Symbol selector (BTC, ETH, SOL, etc.)

<OrderPanel />
  - Market / Limit toggle
  - Buy / Sell buttons
  - Quantity input (USD or coin denominated)
  - Leverage slider (1x–20x)
  - Submit → POST /api/orders/create

<PositionsTable />
  - Symbol, side, size, entry price, mark price, unrealized PnL, actions
  - "Close" button per position → POST /api/orders/close-position

<OrderHistoryTable />
  - Paginated, last 50 orders
  - From Supabase orders table (synced from Bybit)
```

---

### Module 3: Order Proxy (`/api/orders/`)

**All order API routes use service-role Supabase to fetch encrypted API keys — never exposed to client**

```
POST /api/orders/create
  Auth: Supabase JWT (user must own funded account)
  Body: { symbol, side, orderType, qty, price?, leverage? }
  1. Load funded_account for user
  2. Validate: status === 'active'
  3. Validate: current_equity > floor_equity (guard — risk engine is primary)
  4. Decrypt sub-account API keys
  5. Set leverage: POST /v5/position/set-leverage
  6. Forward order: POST /v5/order/create
  7. Store in orders table
  Returns: { orderId, status }

POST /api/orders/cancel
  Body: { orderId }
  → Cancel on Bybit, update orders table

POST /api/orders/close-position
  Body: { symbol }
  → Place reduce-only market order for full position size
```

---

### Module 4: Withdrawal Flow (`/withdraw`)

**Eligibility rules:**
- `funded_accounts.status === 'active'`
- `current_equity > funded_amount` (must be in profit)
- No open positions at time of withdrawal request
- Minimum withdrawal: $50 profit

**API Route:**

```
POST /api/withdrawals/request
  1. Verify user is in profit: equity - 2000 = gross_profit
  2. Calculate: trader_share = gross_profit * 0.80
  3. Calculate: platform_fee = gross_profit * 0.20
  4. Create withdrawals row: status = pending
  5. Transfer trader_share from sub-account → master (Bybit inter-transfer)
  6. Transfer trader_share from master → user's exchange withdrawal address
     (or: platform sends USDT to Solana wallet via Bybit withdrawal API)
  7. Update funded_account: current_equity → 2000 (reset to principal)
     (account continues — user can trade again from $2,000)
  8. Log platform_fee in fee_ledger
  Returns: { withdrawalId, amount, estimatedArrival }
```

---

## 7. Risk Engine Design

### Overview

The risk engine is a **standalone Node.js process** deployed separately from Next.js. It is the most critical component — a bug here could cost real money. It runs continuously, maintaining WebSocket connections to every active sub-account.

### Architecture

```typescript
// risk-engine/index.ts

class RiskEngine {
  private connections: Map<string, WebsocketClient>;  // fundedAccountId → WS
  private equityCache: Map<string, number>;            // fundedAccountId → last equity
  private positionCache: Map<string, Position[]>;
  private liquidationInProgress: Set<string>;          // prevent double-liquidation
  
  async start() {
    // 1. Load all active funded accounts from Supabase
    // 2. Connect WebSocket for each
    // 3. Subscribe to Supabase Realtime for new accounts
    // 4. Run heartbeat poll every 30s as fallback
  }
  
  async onEquityUpdate(fundedAccountId: string, equity: number) {
    this.equityCache.set(fundedAccountId, equity);
    await this.updateSupabase(fundedAccountId, equity);
    await this.checkBreach(fundedAccountId, equity);
  }
  
  async checkBreach(fundedAccountId: string, equity: number) {
    if (this.liquidationInProgress.has(fundedAccountId)) return;
    
    const account = await this.getAccount(fundedAccountId);
    if (equity <= account.floor_equity_usd) {
      await this.liquidate(fundedAccountId, equity);
    } else if (equity <= account.floor_equity_usd + 20) {
      await this.sendWarning(fundedAccountId, equity);  // $20 warning buffer
    }
  }
  
  async liquidate(fundedAccountId: string, currentEquity: number) {
    this.liquidationInProgress.add(fundedAccountId);
    
    try {
      // Log the event immediately
      await this.logRiskEvent(fundedAccountId, 'breach_detected', currentEquity);
      
      // Step 1: Cancel all open orders
      await bybitClient.cancelAllOrders({ category: 'linear' });
      
      // Step 2: Close all positions (market orders)
      const positions = this.positionCache.get(fundedAccountId) ?? 
                        await bybitClient.getPositions({ category: 'linear' });
      
      await Promise.all(
        positions
          .filter(p => parseFloat(p.size) > 0)
          .map(p => this.closePosition(bybitClient, p))
      );
      
      // Step 3: Wait for positions to clear (poll with exponential backoff)
      await this.waitForClear(bybitClient, { maxAttempts: 10, baseDelay: 500 });
      
      // Step 4: Transfer remaining balance back to master
      const finalBalance = await bybitClient.getWalletBalance('UNIFIED');
      const transferAmount = finalBalance.totalWalletBalance;
      
      if (parseFloat(transferAmount) > 0.1) {
        await masterBybitClient.transferFunds({
          from: subUid,
          to: masterUid,
          amount: transferAmount,
          coin: 'USDT'
        });
      }
      
      // Step 5: Update DB
      await supabase
        .from('funded_accounts')
        .update({ status: 'blown', blown_at: new Date().toISOString() })
        .eq('id', fundedAccountId);
      
      await this.logRiskEvent(fundedAccountId, 'liquidation_complete', currentEquity);
      
      // Step 6: Notify user
      await this.notifyUser(fundedAccountId, 'account_blown');
      
      // Step 7: Disconnect WebSocket for this account
      this.connections.get(fundedAccountId)?.closeAll();
      this.connections.delete(fundedAccountId);
      
    } catch (error) {
      // CRITICAL: log to monitoring, alert ops team
      logger.error('LIQUIDATION FAILED', { fundedAccountId, error });
      await this.alertOpsTeam(fundedAccountId, error);
    } finally {
      this.liquidationInProgress.delete(fundedAccountId);
    }
  }
}
```

### Fallback Poll (Safety Net)

Even with WebSockets, implement a polling fallback every 30 seconds for all active accounts. WebSockets can disconnect silently. If a poll finds equity below floor, trigger liquidation.

```typescript
// Cron: every 30 seconds
async pollAllActiveAccounts() {
  const accounts = await supabase
    .from('funded_accounts')
    .select('id, bybit_sub_uid, floor_equity_usd')
    .eq('status', 'active');
    
  for (const account of accounts.data) {
    const equity = await getEquityFromBybit(account.bybit_sub_uid);
    await this.onEquityUpdate(account.id, equity);
  }
}
```

### Deployment (Railway)

```yaml
# railway.toml
[deploy]
  startCommand = "node dist/index.js"
  restartPolicyType = "always"
  
[env]
  NODE_ENV = "production"
```

The risk engine must have **zero downtime** — configure Railway to restart immediately on crash, and set up alerting (Better Stack) for any restart events.

---

## 8. Build Phases

### Phase 1 — Foundation

**Goal**: Working auth + DB + Bybit connection

- [ ] Set up Next.js project with TypeScript, Tailwind, Supabase
- [ ] Implement wallet auth (Reown AppKit, Solana)
- [ ] Run all SQL migrations (schema above)
- [ ] Bybit master account setup + API key creation
- [ ] Test Bybit sub-account creation manually (Postman/curl)
- [ ] Test inter-account transfer (master → sub)
- [ ] Implement `funded_account_secrets` encryption/decryption helper
- [ ] Build `/api/onboard/verify-deposit` (Solana USDT verification)
- [ ] Build `/api/onboard/provision-account` (Bybit setup flow)
- [ ] End-to-end test: pay → sub-account created → funded → status active

**Deliverable**: User can pay $100, see "Account Activated" state in DB

---

### Phase 2 — Trading Interface

**Goal**: Users can place and manage trades

- [ ] Build order proxy routes (`/api/orders/create`, `/cancel`, `/close-position`)
- [ ] Build `<OrderPanel />` component with market/limit modes
- [ ] Integrate TradingView Lightweight Charts (candlestick)
- [ ] Build `<PositionsTable />` (live from Bybit REST, poll every 5s)
- [ ] Build `<OrderHistoryTable />` (from Supabase orders table)
- [ ] Implement leverage setter (call Bybit set-leverage before order)
- [ ] Symbol selector (BTC, ETH, SOL, XRP — start with 4 pairs)
- [ ] Test full trade flow: open position → hold → close manually
- [ ] Supabase Realtime subscription for live order status updates

**Deliverable**: User can open/close positions from the UI

---

### Phase 3 — Risk Engine

**Goal**: Auto-liquidation working and tested

- [ ] Set up standalone Node.js risk engine project
- [ ] Implement `RiskEngine` class with WebSocket connection pool
- [ ] Implement `liquidate()` function with all 7 steps
- [ ] Implement 30-second polling fallback
- [ ] Implement `<DrawdownMeter />` component (live equity from Supabase Realtime)
- [ ] Equity snapshot writes (every WebSocket update → insert equity_snapshots)
- [ ] Supabase Realtime: push equity updates to frontend
- [ ] Test liquidation: manually drop sub-account equity below floor → verify all steps
- [ ] Deploy risk engine to Railway
- [ ] Set up monitoring alerts (Better Stack webhook on Railway restarts)

**Deliverable**: Liquidation fires automatically, balance returned to master

---

### Phase 4 — Withdrawal & Settlement

**Goal**: Profitable traders can withdraw

- [ ] Build `/api/withdrawals/request` route
- [ ] Build `<WithdrawPage />` with profit display and confirm button
- [ ] Implement Bybit → user withdrawal flow (platform sends USDT to wallet)
- [ ] Fee ledger logging for every withdrawal
- [ ] Re-fund logic: after withdrawal, reset account equity to $2,000 (account stays active)
- [ ] Withdrawal history page

**Deliverable**: Profitable trader can request and receive their share

---

### Phase 5 — Polish & Launch Prep

**Goal**: Production-ready

- [ ] Leaderboard page (top traders by profit %, from public funded_accounts data)
- [ ] Email notifications (Resend): account blown, withdrawal processed, equity warning
- [ ] Rate limiting on all API routes (Upstash Redis)
- [ ] Error boundary coverage across all pages
- [ ] Loading states on all async operations
- [ ] Mobile responsive layout
- [ ] Bybit testnet end-to-end test of full lifecycle
- [ ] Security audit: ensure no API keys in client bundle, all secrets server-side
- [ ] Set up production Supabase project (not dev)
- [ ] Configure production Bybit master account (real USDT)
- [ ] Load test: 50 concurrent sub-accounts with active risk engine

**Deliverable**: Ship to first 10 beta users

---

## 9. Environment Variables

```bash
# ── Supabase ──
NEXT_PUBLIC_SUPABASE_URL=
NEXT_PUBLIC_SUPABASE_ANON_KEY=
SUPABASE_SERVICE_ROLE_KEY=           # server-side only, never in client bundle

# ── Bybit (Master Account) ──
BYBIT_MASTER_API_KEY=                # server-side only
BYBIT_MASTER_API_SECRET=             # server-side only
BYBIT_MASTER_UID=                    # your master account UID

# ── Bybit Config ──
BYBIT_TESTNET=false                  # set true for development
BYBIT_FUNDED_AMOUNT=2000             # USDT to fund per account
BYBIT_FLOOR_EQUITY=1900              # liquidation trigger
BYBIT_PROFIT_SHARE_TRADER=0.80       # 80% to trader

# ── Solana ──
NEXT_PUBLIC_SOLANA_RPC_URL=          # Helius RPC endpoint
PLATFORM_FEE_WALLET=                 # your USDT collection wallet address
PLATFORM_FEE_WALLET_PRIVATE_KEY=     # server-side only (for outgoing withdrawals)

# ── Reown / AppKit ──
NEXT_PUBLIC_REOWN_PROJECT_ID=

# ── Encryption (for Bybit sub-account keys at rest) ──
ENCRYPTION_KEY=                      # 32-byte hex string, AES-256-GCM
ENCRYPTION_IV_LENGTH=12

# ── Upstash Redis (rate limiting + risk engine cache) ──
UPSTASH_REDIS_REST_URL=
UPSTASH_REDIS_REST_TOKEN=

# ── Resend (email) ──
RESEND_API_KEY=

# ── Monitoring ──
BETTERSTACK_SOURCE_TOKEN=

# ── Risk Engine (Railway env vars) ──
RISK_ENGINE_POLL_INTERVAL_MS=30000
RISK_ENGINE_EQUITY_WARNING_BUFFER=20  # warn when $20 above floor
```

---

## 10. Legal & Compliance

> ⚠️ **This section is critical. Read before launching.**

### Regulatory Classification Risk

Depending on jurisdiction, this product may be classified as:
- A **derivative trading platform** (requires FCA, SEC, MAS, or equivalent license)
- A **proprietary trading firm** (generally unregulated in most jurisdictions)
- A **gambling product** (in some jurisdictions if not framed correctly)

### Recommended Initial Structure

1. **Incorporate in a favourable jurisdiction**: British Virgin Islands, Cayman Islands, or UAE (DMCC) are common for crypto prop firms. Avoid US incorporation initially.
2. **Geo-block US, UK, and EU users** at launch until legal review complete. Use IP detection + wallet-based geo signals.
3. **Terms of Service must clearly state**:
   - Entry fee is non-refundable
   - Platform provides access to funded accounts, not financial advice
   - Funded capital is platform property, not user funds
   - User is trading on platform's behalf under agreed rules
4. **This is NOT a deposit** — the $100 fee is a service fee for access, not a user deposit. This distinction is legally significant.
5. **KYC**: Not required at MVP, but have a plan for when volume grows. Synaps or Persona integrate well with Supabase.

### Bybit Terms Compliance

- Verify that automated sub-account creation is permitted under your Bybit account tier
- Bybit may require additional verification for programmatic sub-account management
- Ensure your use case is not violating Bybit's Terms of Service regarding third-party fund management
- Consider reaching out to Bybit business/institutional team for a formal arrangement at scale

---

## 11. Go-Live Checklist

### Security
- [ ] Zero client-side exposure of Bybit API keys (verify with `next build` bundle analysis)
- [ ] All Supabase service_role queries are server-side only
- [ ] AES-256-GCM encryption on stored sub-account API keys
- [ ] RLS policies tested (user cannot query another user's data)
- [ ] Rate limiting on all mutation API routes
- [ ] Solana tx verification requires `finalized` commitment (not `confirmed`)
- [ ] Idempotency keys on all Bybit transfer calls (prevent double-transfers)

### Financial Safety
- [ ] Bybit master account funded with sufficient treasury (minimum 2× expected active accounts)
- [ ] Risk engine running and tested with real sub-accounts on testnet
- [ ] Manual override: documented runbook for emergency liquidation of all accounts
- [ ] Monitoring alert fires within 60 seconds of risk engine going offline
- [ ] Tested: double-liquidation prevention (liquidationInProgress set)
- [ ] Tested: partial position close handling (if close order partially fills)
- [ ] Treasury reconciliation script ready (master balance = sum of all sub-accounts + fee vault)

### Operational
- [ ] Withdrawal process tested end-to-end
- [ ] User notification emails tested (blown account, withdrawal, warning)
- [ ] Leaderboard live
- [ ] Support contact method visible (Telegram/Discord)
- [ ] Status page or uptime monitor public URL

---

## Appendix: Bybit Testnet Setup

For development, use Bybit testnet:
- Testnet URL: `https://api-testnet.bybit.com`
- Set `BYBIT_TESTNET=true` in `.env.local`
- Create testnet account at `testnet.bybit.com`
- Fund testnet account with test USDT (available from testnet faucet)
- All sub-account creation, transfers, and trading work identically on testnet

## Appendix: Recommended Pairs to Launch With

Start with 4 high-liquidity perpetuals to keep risk engine position-closing fast:

| Symbol | Typical Spread | Max Suggested Leverage |
|--------|---------------|----------------------|
| BTCUSDT | ~0.01% | 20x |
| ETHUSDT | ~0.02% | 20x |
| SOLUSDT | ~0.03% | 10x |
| XRPUSDT | ~0.03% | 10x |

Do NOT offer low-liquidity tokens at launch — closing positions fast during liquidation requires deep order books.

---