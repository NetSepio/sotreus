import Link from 'next/link';
import type { Metadata } from 'next';

const sections = [
  {
    title: '1. Service scope',
    body:
      'SOTREUS provides a website, funded-account offers, dashboards, trading interfaces, wallet-connected workflows, payout tooling, and related support services for crypto traders. SOTREUS is a technology and account-access platform. It is not a bank, a broker-dealer, an investment adviser, or a fiduciary.',
    bullets: [
      'Marketing pages are summaries, not the full rule book.',
      'The live rule set shown at checkout or inside the dashboard is part of these Terms.',
    ],
  },
  {
    title: '2. Eligibility and restricted use',
    body:
      'You must be at least 18 years old and legally allowed to use SOTREUS in your jurisdiction. You may not use the Services if local law, sanctions rules, or platform policy prohibit it.',
    bullets: [
      'SOTREUS may limit or deny access by country, wallet, user, or payment method.',
      'You are responsible for knowing whether crypto trading products are permitted where you live.',
    ],
  },
  {
    title: '3. Purchases, fees, and tier access',
    body:
      'Launch tiers currently marketed at $99, $499, and $999 are paid-access products. Unless a campaign, refund policy, or applicable law says otherwise, fees are treated as non-refundable once the account setup process begins.',
    bullets: [
      'The fee gives you access to the purchased account tier and its active rule set.',
      'If pricing, features, or eligibility rules change, the version shown at checkout controls that purchase.',
    ],
  },
  {
    title: '4. Account rules and risk controls',
    body:
      'Each account operates under defined limits that may include funded balance, loss floor, maximum drawdown, permitted products, payout conditions, inactivity rules, and prohibited trading behavior. If you breach an account rule, SOTREUS may limit, suspend, reset, or terminate the account.',
    bullets: [
      'Rule tables shown in the live product override simplified marketing copy if there is a conflict.',
      'You are responsible for monitoring your account status and understanding the active loss parameters.',
    ],
  },
  {
    title: '5. Payouts and profit share',
    body:
      'Payout eligibility depends on the live plan terms, account behavior, fraud screening, risk review, and any verification required by law or policy. Marketing statements such as "up to 90%" apply only where that share is actually supported by the active product rules.',
    bullets: [
      'SOTREUS may delay, deny, or reverse a payout request if a rule breach, misuse, or compliance issue is detected.',
      'Payout timing, minimum thresholds, and trader share percentages should be reviewed in the live account policy before you trade.',
    ],
  },
  {
    title: '6. Verification, compliance, and anti-fraud controls',
    body:
      'SOTREUS may request identity information, wallet ownership evidence, source-of-funds details, sanctions screening, device verification, or other information needed to protect the platform and comply with law.',
    bullets: [
      'Access may be limited if you refuse a required verification step.',
      'Multiple accounts, payment abuse, referral abuse, exploit usage, or attempts to bypass restrictions may result in immediate suspension.',
    ],
  },
  {
    title: '7. No advice and risk disclosure',
    body:
      'Trading digital assets is risky and can result in losses. Nothing on SOTREUS is financial, tax, legal, or investment advice. Past performance, example metrics, and marketing illustrations do not guarantee future results.',
    bullets: [
      'System delays, exchange outages, liquidity changes, and infrastructure failures can affect execution and payouts.',
      'You are solely responsible for your trading decisions and tax obligations.',
    ],
  },
  {
    title: '8. Suspension, liability, and updates',
    body:
      'SOTREUS may suspend or end access to the Services if required by law, for security reasons, or if platform rules are breached. To the maximum extent allowed by law, SOTREUS is not liable for indirect, incidental, special, or consequential damages related to use of the Services.',
    bullets: [
      'SOTREUS may update these Terms from time to time, and the updated version becomes effective when posted.',
      'Your continued use of the Services after an update means you accept the revised Terms.',
    ],
  },
];

export const metadata: Metadata = {
  title: 'Terms of Use',
  description:
    'Read the SOTREUS Terms of Use covering account access, pricing, risk rules, payouts, compliance checks, and platform restrictions.',
};

export default function TermsPage() {
  return (
    <main className="legal-page">
      <div className="video-overlay" />
      <div className="neon-grid" />
      <div className="hero-glow" />

      <div className="legal-shell">
        <Link className="legal-back" href="/">
          <span aria-hidden="true">&larr;</span>
          Back to Home
        </Link>

        <header className="legal-header">
          <div className="live-badge">
            <span className="live-dot" />
            LEGAL
          </div>
          <h1 className="legal-title">Terms of Use</h1>
          <p className="legal-intro">
            These Terms govern how traders access and use the SOTREUS website,
            funded-account offers, dashboards, and related services. Review
            them before purchasing a tier or trading on the platform.
          </p>
          <p className="legal-meta">Last updated: April 5, 2026</p>
        </header>

        <div className="legal-sections">
          {sections.map((section) => (
            <section className="legal-section" key={section.title}>
              <h2>{section.title}</h2>
              <p>{section.body}</p>
              <ul>
                {section.bullets.map((bullet) => (
                  <li key={bullet}>{bullet}</li>
                ))}
              </ul>
            </section>
          ))}
        </div>

        <div className="legal-note">
          Marketing copy is meant to make SOTREUS easier to understand, not to
          replace the operative rule set. If a dashboard rule table or checkout
          disclosure is more specific than a homepage summary, the more specific
          disclosure controls.
        </div>
      </div>
    </main>
  );
}
