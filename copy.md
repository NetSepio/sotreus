# SOTREUS Copy Source of Truth

Last updated: April 5, 2026

## 1. Purpose

This file is the operating brief for any AI agent or human updating SOTREUS marketing copy.

The goal is to keep the site persuasive without inventing facts, overstating legal claims, or drifting away from the product model described in this repository.

If a required fact is unknown, do not make it up. Mark it as `TODO` and stop that claim from appearing in public-facing copy.

## 2. Confirmed inputs

### 2.1 Confirmed from this repository

- `Strategy.md` describes a live funded-account model:
  - user pays about $100
  - platform provisions a funded account
  - current example balance is $2,000
  - the recommended cleaner floor is $1,900
- `Strategy.md` says the profit split is configurable and uses `80% trader / 20% platform` in the economic model example.
- `Plan.md` also uses the hook `Trade $2,000. Risk $100. Keep 80%.`
- The original landing page in `src/app/page.tsx` previously claimed a fixed `90%` split and `No KYC`.
- The codebase originally had no Terms page and no Privacy page.
- The original ticker used fabricated payout-style data. This must never be presented as real proof unless it is backed by verifiable production data.

### 2.2 Confirmed from competitor research reviewed on April 5, 2026

Use these references for market framing only. Do not copy their wording.

- [Mubite](https://mubite.com/en)
  - Markets itself as a crypto prop firm with Instant Funding, One-Step, and Two-Step programs.
  - Shows account sizes ranging from small balances up to much larger balances.
  - Search results for Mubite challenge pricing show:
    - instant funding plan context
    - $10,000 account example starting at $440
    - 70% profit split on that instant funding example
    - max drawdown of $1,000 on the $10,000 instant funding example
- [Solana Funded homepage](https://solanafunded.com/)
  - Positions itself as a Solana-native prop firm.
  - Uses evaluation-based funding rather than a pure instant-funded promise.
  - Public pricing content shows challenge sizes from $2,500 to $100,000.
  - Public marketing claims include up to 90% profit split.
  - Public rule blocks emphasize max drawdown and daily drawdown.
- [Solana Funded docs](https://docs.solanafunded.com/)
  - Frames the product as a structured evaluation leading to funding.
  - Repeats themes of transparency, consistency, and rule clarity.
- [Solana Funded terms](https://www.solanafunded.com/terms-and-conditions)
  - Search results describe the company as an evaluation business and explicitly mention simulated trading environments.

## 3. Strategic conclusion from the research

SOTREUS should not launch by imitating the biggest competitor balances.

The better launch position is:

- smaller entry tiers
- smaller funded balances
- simpler rules
- a more credible first step for newer crypto traders

Competitors are already covering:

- $10k to $100k+ challenge ladders
- evaluation-heavy models
- aggressive scale messaging

SOTREUS should win by owning this position:

> "A Solana-native crypto prop platform for traders who want to start small, trade with discipline, and grow into larger buying power."

## 4. Approved launch offer

### 4.1 Public launch tiers

Use this tier matrix unless the product team approves a different one in writing.

| Tier | Public price | Funded account | Positioning use |
| --- | --- | --- | --- |
| Launch | $99 | $2,000 | first serious prop account |
| Builder | $499 | $10,000 | repeatable setup, more room to size |
| Pro | $999 | $20,000 | biggest launch-phase account |

### 4.2 Internal rationale

This structure is intentionally conservative.

- The current repo already supports the idea of roughly `$100 -> $2,000`.
- Keeping the larger tiers close to the same capital multiple avoids an unbelievable leap.
- It protects treasury exposure while the product is still young.
- It gives newer traders a believable path upward instead of forcing them into oversized plans on day one.

### 4.3 Suggested risk framing

Use this only if product and ops confirm it.

| Tier | Suggested floor | Suggested max loss buffer |
| --- | --- | --- |
| Launch | $1,900 | $100 |
| Builder | $9,500 | $500 |
| Pro | $19,000 | $1,000 |

If these numbers are not operationally confirmed, remove them from public copy and say only:

- clear loss floor
- visible account rules
- discipline-first risk model

## 5. Non-hallucination rules

These rules are mandatory.

### 5.1 Never say this unless it is confirmed

- fixed `90%` profit split across all tiers
- `No KYC` as a universal promise
- instant payouts in all cases
- global availability
- exact withdrawal timing
- exact jurisdiction coverage
- exact supported asset list beyond what the product team confirmed
- real payout examples unless sourced from production records
- broker, exchange, or investment-adviser claims

### 5.2 Safe fallback language

Use these phrases when the exact fact is not finalized:

- `up to 90% trader share`
- `wallet-first onboarding`
- `verification may be required where law or policy requires it`
- `review the live account rules before purchase`
- `eligible markets are shown in the live product`
- `payouts are subject to the active plan terms and platform review`

### 5.3 Current repo conflicts that must stay resolved

- `Strategy.md` and `Plan.md` imply 80%.
- the original homepage implied 90%.
- Until operations confirms the final split table, homepage copy should use `up to 90%`, not a universal fixed percentage.

## 6. Brand narrative and positioning

### 6.1 Core positioning

SOTREUS is not "the loudest crypto prop firm."

SOTREUS is:

- smaller
- clearer
- more disciplined
- more believable for first-time prop traders

### 6.2 Audience

Primary audience:

- traders who understand crypto markets but have never used a prop platform
- early-stage perpetual traders who want structure without a large upfront fee
- Solana-native traders who prefer wallet-connected flows and 24/7 market access

Secondary audience:

- traders who dislike multi-step evaluation funnels
- traders who want smaller funded accounts before moving into bigger capital programs

### 6.3 Tone

Use this tone:

- confident
- clear
- disciplined
- modern
- crypto-native

Avoid this tone:

- casino-like
- macho
- fake-elite
- hype-first
- reckless

### 6.4 Core messaging pillars

Every homepage revision should reinforce these pillars:

1. Start small.
2. Trade with discipline.
3. Read the rules before you buy.
4. Grow into larger buying power only after trust is earned.

## 7. Homepage requirements

### 7.1 SEO and metadata

Homepage title direction:

- `SOTREUS | Crypto Prop Accounts That Let Traders Start Small`

Homepage description direction:

- mention the three launch tiers
- mention funded balances from $2,000 to $20,000
- mention wallet-first onboarding
- mention clear rules

Required metadata themes:

- searchable terms like `crypto prop trading`, `funded crypto account`, `solana prop trading`
- Open Graph and Twitter copy must match the homepage promise
- the share image should show the tier ladder, not vague branding

### 7.2 Hero

The hero must communicate all of this above the fold:

- SOTREUS is a crypto prop platform
- it is designed for traders who want to start small
- the launch tiers are `$99`, `$499`, `$999`
- the funded range is `$2,000` to `$20,000`

Approved hero idea:

> Crypto Prop Accounts That Let Traders Start Small.

### 7.3 Trust bar / ticker

The ticker should use product facts, not invented social proof.

Allowed ticker items:

- tier sizes
- funded account sizes
- supported market categories
- risk-rule reminders
- payout-policy reminders

Forbidden ticker items:

- fake wallet addresses
- fake payouts
- fake timestamps
- fake trader testimonials

### 7.4 Pricing section

The pricing section must appear before deep narrative sections.

Each card must show:

- tier name
- public price
- funded account size
- who the tier is for
- short proof points

The section must also say that the launch tiers are intentionally conservative.

### 7.5 Risk section

The site should show a discipline-first risk frame, not a high-leverage fantasy.

The section should communicate:

- visible loss floors
- clear breach conditions
- readable rules before checkout

### 7.6 Narrative section

This section should explain why SOTREUS exists.

Approved narrative direction:

- new traders need clarity before scale
- smaller starting tiers are a feature, not a weakness
- trust comes from readable rules, not louder headlines

### 7.7 Footer

The footer must include:

- Terms link
- Privacy link
- a short brand line aligned with the small-entry positioning

## 8. Terms page requirements

The Terms page must cover at minimum:

1. Service scope
2. Eligibility and restricted use
3. Purchases and fees
4. Account rules and breach handling
5. Payout eligibility and profit-share conditions
6. Verification, compliance, and anti-fraud
7. No-advice and risk disclosure
8. Suspension, liability, and updates

Mandatory legal-copy rules:

- do not call the platform a broker or adviser
- do not guarantee payouts
- say that live dashboard rules and checkout disclosures control over simplified homepage summaries
- say that verification may be required

Pre-launch TODOs that must be inserted before production:

- legal entity name
- company address
- support email
- governing law
- restricted-jurisdiction list

## 9. Privacy page requirements

The Privacy page must cover at minimum:

1. what data is collected
2. why the data is used
3. cookies and analytics
4. sharing with vendors and legal authorities
5. retention
6. security
7. user rights and choices
8. policy updates

Mandatory privacy-copy rules:

- mention wallet and transaction data
- mention trading activity data
- mention fraud and compliance uses
- mention that policy must match actual production tooling

Pre-launch TODOs that must be inserted before production:

- official privacy contact email
- exact vendor list if required by counsel
- exact user-rights workflow

## 10. Copy style rules

### 10.1 Preferred vocabulary

Use:

- funded account
- crypto prop platform
- launch tier
- wallet-first onboarding
- clear rules
- loss floor
- trader share
- disciplined growth

Prefer not to use:

- PropAMM as the main SEO phrase
- activate terminal
- no-questions-asked payouts
- no KYC
- instant riches
- unlimited opportunity

`PropAMM` can stay as a secondary internal concept, but public-facing copy should lead with searchable terms like `prop trading`, `funded account`, and `crypto prop platform`.

### 10.2 Writing rules

- Keep claims concrete.
- Put the number next to the promise.
- If the number is not confirmed, remove the promise.
- Use short sentences in the hero and pricing sections.
- Use more careful legal language in Terms and Privacy.

## 11. Step-by-step update instructions for future AI agents

Follow this order exactly.

1. Read `Strategy.md`, `Plan.md`, and the live `src/app` marketing copy.
2. Confirm whether the launch tiers are still `$99`, `$499`, and `$999`.
3. Confirm whether the funded balances are still `$2,000`, `$10,000`, and `$20,000`.
4. Confirm whether the trader share is fixed or should remain `up to 90%`.
5. Confirm whether any KYC or compliance checks exist for payouts or certain jurisdictions.
6. If any of steps 2 through 5 are unknown, remove the claim from public copy and replace it with safe fallback language.
7. Update homepage metadata first.
8. Update hero and pricing copy second.
9. Update Terms and Privacy before publishing any new marketing claim.
10. Verify that no ticker, testimonial, payout counter, or review quote is fabricated.
11. Verify that every number on the homepage matches either:
    - the confirmed product configuration, or
    - a clearly labeled example
12. Re-read the final copy and remove any sentence that sounds stronger than the policy pages.

## 12. Acceptance checklist

The copy work is acceptable only if all of the following are true:

- the homepage clearly communicates the three launch tiers
- the site feels built for new and growing traders, not whales only
- the copy leads with searchable prop-trading language
- the site does not promise `No KYC` without confirmation
- the site does not present fake payouts as real proof
- Terms and Privacy exist and are linked
- any unresolved legal facts are left as TODOs in planning documents, not invented in public copy
- the narrative stays disciplined, credible, and easy to trust
