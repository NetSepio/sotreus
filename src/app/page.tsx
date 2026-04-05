import Link from 'next/link';
import BentoGrid from './components/BentoGrid';
import DrawdownMeter from './components/DrawdownMeter';
import PayoutTicker from './components/PayoutTicker';

const launchTiers = [
  {
    name: 'Launch',
    fee: '$99',
    funded: '$2,000',
    audience: 'For first-time funded traders who want structure without oversized cost.',
    points: [
      "Built for a trader's first serious prop account",
      'Major crypto perpetuals first',
      'Clear rules before checkout',
    ],
  },
  {
    name: 'Builder',
    fee: '$499',
    funded: '$10,000',
    audience: 'For traders with a repeatable setup who need more room to express it.',
    points: [
      'More sizing room without a giant leap',
      'Same discipline-first account logic',
      'Designed for steady progression',
    ],
  },
  {
    name: 'Pro',
    fee: '$999',
    funded: '$20,000',
    audience: 'For disciplined traders ready for the biggest launch-phase account.',
    points: [
      'Highest launch-tier buying power',
      'Built for proven routines, not impulse',
      'Expansion-friendly without going oversized',
    ],
  },
];

const howItWorks = [
  {
    step: '01',
    title: 'Choose Your Tier',
    description:
      'Review the Launch, Builder, and Pro plans side by side. Read the active rules, eligible markets, and payout terms before you pay.',
  },
  {
    step: '02',
    title: 'Activate Your Account',
    description:
      'Connect your wallet, complete payment, and finish any required anti-fraud or compliance checks tied to the live product policy.',
  },
  {
    step: '03',
    title: 'Trade With Discipline',
    description:
      'Trade eligible crypto markets within your loss limits. Request payouts under the live plan terms once your account becomes eligible.',
  },
];

const positioningCards = [
  {
    value: '$99',
    label: 'A smaller first step for new prop traders',
  },
  {
    value: 'Terms + Privacy',
    label: 'Readable policy pages before checkout',
  },
  {
    value: 'Up to 90%',
    label: 'Use this headline only where the live plan supports it',
  },
];

export default function Home() {
  return (
    <div className="page-wrapper">
      {/* ── BACKGROUND VIDEO ── */}
      <div className="video-bg">
        <video
          src="https://d8j0ntlcm91z4.cloudfront.net/user_38xzZboKViGWJOttwIXH07lWA1P/hf_20260217_030345_246c0224-10a4-422c-b324-070b7c0eceda.mp4"
          autoPlay
          loop
          muted
          playsInline
        />
      </div>
      <div className="video-overlay" />

      {/* ── NEON GRID OVERLAY ── */}
      <div className="neon-grid" />

      {/* ── FLOATING PARTICLES ── */}
      <div className="particles">
        <div className="particle" />
        <div className="particle" />
        <div className="particle" />
        <div className="particle" />
        <div className="particle" />
        <div className="particle" />
        <div className="particle" />
        <div className="particle" />
      </div>

      {/* ── GLOW ORBS ── */}
      <div className="hero-glow" />
      <div className="glow-orb-secondary" />

      {/* ══ ALL CONTENT ══ */}
      <div className="content-layer">
        {/* ── NAVBAR ── */}
        <div className="container">
          <nav className="navbar" id="navbar">
            <Link href="/" className="nav-logo">
              SOTREUS
            </Link>
            <a className="nav-cta" id="nav-funding-tiers" href="#tiers">
              <svg
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <polyline points="4 17 10 11 4 5" />
                <line x1="12" y1="19" x2="20" y2="19" />
              </svg>
              See Funding Tiers
            </a>
          </nav>
        </div>

        {/* ── HERO SECTION ── */}
        <div className="container">
          <section className="hero" id="hero-section">
            {/* Live badge */}
            <div className="live-badge">
              <span className="live-dot" />
              SOLANA-NATIVE PROP PLATFORM
            </div>

            {/* Massive headline */}
            <h1 className="hero-headline">Crypto Prop Accounts That Let Traders Start Small.</h1>

            {/* Sub-headline */}
            <p className="hero-sub">
              SOTREUS gives new and growing traders a clearer first step into
              prop trading with <strong className="font-mono">$99</strong>,{' '}
              <strong className="font-mono">$499</strong>, and{' '}
              <strong className="font-mono">$999</strong> launch tiers and
              funded account sizes from{' '}
              <strong className="font-mono">$2,000</strong> to{' '}
              <strong className="font-mono">$20,000</strong>.
            </p>

            {/* Glowing CTA */}
            <a className="hero-cta" id="hero-get-started" href="#tiers">
              View Funding Tiers
              <svg
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <line x1="5" y1="12" x2="19" y2="12" />
                <polyline points="12 5 19 12 12 19" />
              </svg>
            </a>
            <p className="hero-note">
              Review the <Link href="/terms">Terms</Link> and{' '}
              <Link href="/privacy">Privacy Policy</Link> before purchasing a
              tier.
            </p>

            {/* Hero stats */}
            <div className="hero-stats">
              <div className="hero-stat">
                <span className="hero-stat-value">$99</span>
                <span className="hero-stat-label">Smallest Entry Tier</span>
              </div>
              <div className="hero-stat">
                <span className="hero-stat-value">$2K-$20K</span>
                <span className="hero-stat-label">Funded Accounts</span>
              </div>
              <div className="hero-stat">
                <span className="hero-stat-value">Up to 90%</span>
                <span className="hero-stat-label">Trader Share</span>
              </div>
              <div className="hero-stat">
                <span className="hero-stat-value">24/7</span>
                <span className="hero-stat-label">Crypto Markets</span>
              </div>
            </div>
          </section>
        </div>

        {/* ── TRUST BAR / PAYOUT TICKER ── */}
        <section className="trust-section" id="trust-ticker">
          <div className="container">
            <div className="trust-label">
              Launch With Clear Rules: <span>$99</span> entry &middot;{' '}
              <span>$2K-$20K</span> funded accounts &middot;{' '}
              <span>BTC / ETH / SOL / XRP</span> liquid majors first
            </div>
          </div>
          <PayoutTicker />
        </section>

        {/* ── FUNDING TIERS ── */}
        <div className="container">
          <section className="pricing-section" id="tiers">
            <div className="section-label">Funding Tiers</div>
            <h2 className="section-title">
              Three launch plans built for traders who want a credible first
              step.
            </h2>
            <div className="pricing-grid">
              {launchTiers.map((tier, index) => (
                <article
                  className={`pricing-card ${index === 0 ? 'featured' : ''}`}
                  key={tier.name}
                >
                  <div className="pricing-card-top">
                    <div>
                      <p className="pricing-tier">{tier.name}</p>
                      <h3 className="pricing-fee">{tier.fee}</h3>
                    </div>
                    <span className="pricing-funded">{tier.funded} funded</span>
                  </div>
                  <p className="pricing-copy">{tier.audience}</p>
                  <div className="pricing-points">
                    {tier.points.map((point) => (
                      <span className="pricing-point" key={point}>
                        {point}
                      </span>
                    ))}
                  </div>
                </article>
              ))}
            </div>
            <div className="policy-note">
              <p>
                These launch tiers are intentionally conservative. SOTREUS
                should earn trust with smaller plans before expanding into
                larger balances.
              </p>
              <div className="policy-links">
                <Link href="/terms">Read Terms</Link>
                <Link href="/privacy">Read Privacy Policy</Link>
              </div>
            </div>
          </section>
        </div>

        {/* ── BENTO GRID FEATURES ── */}
        <div className="container">
          <section className="features-section" id="features-section">
            <div className="section-label">Positioning</div>
            <h2 className="section-title">A better first prop account for crypto traders.</h2>
            <BentoGrid />
          </section>
        </div>

        {/* ── RISK SECTION ── */}
        <div className="container">
          <section className="risk-section" id="risk-section">
            <div className="risk-grid">
              <div className="risk-copy">
                <div className="section-label risk-label">Risk Controls</div>
                <h2 className="risk-title">Smaller accounts. Stronger habits.</h2>
                <p className="risk-body">
                  SOTREUS should reward discipline, not lottery-style leverage.
                  Each account needs visible loss limits, readable breach
                  conditions, and a payout process traders can understand before
                  they ever place a trade.
                </p>
                <ul className="risk-list">
                  <li>Starter example shown: $2,000 account with a $1,900 floor.</li>
                  <li>Higher tiers should preserve the same discipline-first logic.</li>
                  <li>Read the live payout and verification policy before trading.</li>
                </ul>
              </div>
              <DrawdownMeter equity={1978.24} floor={1900} funded={2000} />
            </div>
          </section>
        </div>

        {/* ── POSITIONING CTA SECTION ── */}
        <div className="container">
          <section className="payout-section" id="positioning-section">
            <div className="section-label">Brand Narrative</div>
            <h2 className="payout-section-title">
              Built for traders who want clarity before they want scale.
            </h2>
            <p className="payout-section-sub">
              New traders do not need louder promises. They need smaller entry
              costs, cleaner copy, and product rules that are easy to find,
              easy to read, and hard to misunderstand.
            </p>
            <div className="payout-cards">
              {positioningCards.map((card) => (
                <div className="payout-card" key={card.value}>
                  <span className="payout-card-value font-mono">{card.value}</span>
                  <span className="payout-card-label">{card.label}</span>
                </div>
              ))}
            </div>
          </section>
        </div>

        {/* ── HOW IT WORKS ── */}
        <div className="container">
          <section className="features-section" id="how-it-works">
            <div className="section-label">How It Works</div>
            <h2 className="section-title">Three steps to start your prop journey.</h2>
            <div className="bento-grid">
              {howItWorks.map((item) => (
                <div className="bento-card" key={item.step}>
                  <div className="bento-icon">
                    <span
                      className="font-mono"
                      style={{ fontSize: '1.25rem', fontWeight: 700 }}
                    >
                      {item.step}
                    </span>
                  </div>
                  <h3 className="bento-card-title">{item.title}</h3>
                  <p className="bento-card-desc">{item.description}</p>
                </div>
              ))}
            </div>
          </section>
        </div>

        {/* ── FOOTER ── */}
        <div className="container">
          <footer className="footer" id="footer">
            <div className="footer-inner">
              <div className="footer-brand">
                <span>
                  &copy; {new Date().getFullYear()} SOTREUS. Smaller crypto prop tiers
                  for traders who want a better first step.
                </span>
                <div className="footer-links">
                  <Link href="/terms">Terms</Link>
                  <Link href="/privacy">Privacy</Link>
                </div>
              </div>
              <div className="footer-socials">
                <a
                  href="https://x.com/CyreneAI"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="footer-social-link"
                  aria-label="X (Twitter)"
                >
                  <svg
                    width="16"
                    height="16"
                    viewBox="0 0 24 24"
                    fill="currentColor"
                  >
                    <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
                  </svg>
                </a>
                <a
                  href="https://github.com/sotreus"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="footer-social-link"
                  aria-label="GitHub"
                >
                  <svg
                    width="18"
                    height="18"
                    viewBox="0 0 24 24"
                    fill="currentColor"
                  >
                    <path
                      fillRule="evenodd"
                      clipRule="evenodd"
                      d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z"
                    />
                  </svg>
                </a>
              </div>
            </div>
          </footer>
        </div>
      </div>
    </div>
  );
}
