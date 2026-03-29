import BentoGrid from './components/BentoGrid';
import PayoutTicker from './components/PayoutTicker';

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
            <a href="/" className="nav-logo">
              SOTREUS
            </a>
            <button className="nav-cta" id="nav-activate-terminal">
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
              Activate Terminal
            </button>
          </nav>
        </div>

        {/* ── HERO SECTION ── */}
        <div className="container">
          <section className="hero" id="hero-section">
            {/* Live badge */}
            <div className="live-badge">
              <span className="live-dot" />
              LIVE ON SOLANA
            </div>

            {/* Massive headline */}
            <h1 className="hero-headline">
              The First Prop-AMM for the Fast-Mover.
            </h1>

            {/* Sub-headline */}
            <p className="hero-sub">
              Get <strong className="font-mono">$2,000</strong> in trading
              capital. No KYC. No evaluations. Just execution.
            </p>

            {/* Glowing CTA */}
            <button className="hero-cta" id="hero-get-started">
              Get Started
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
            </button>

            {/* Hero stats */}
            <div className="hero-stats">
              <div className="hero-stat">
                <span className="hero-stat-value">$2,000</span>
                <span className="hero-stat-label">Funded Capital</span>
              </div>
              <div className="hero-stat">
                <span className="hero-stat-value">$100</span>
                <span className="hero-stat-label">Entry Fee</span>
              </div>
              <div className="hero-stat">
                <span className="hero-stat-value">90%</span>
                <span className="hero-stat-label">Profit Split</span>
              </div>
              <div className="hero-stat">
                <span className="hero-stat-value">&lt;100ms</span>
                <span className="hero-stat-label">Execution</span>
              </div>
            </div>
          </section>
        </div>

        {/* ── TRUST BAR / PAYOUT TICKER ── */}
        <section className="trust-section" id="trust-ticker">
          <div className="container">
            <div className="trust-label">
              Sotreus Engine: <span>$0.00</span> Slippage &middot;{' '}
              <span>&lt;100ms</span> Execution
            </div>
          </div>
          <PayoutTicker />
        </section>

        {/* ── BENTO GRID FEATURES ── */}
        <div className="container">
          <section className="features-section" id="features-section">
            <div className="section-label">Features</div>
            <h2 className="section-title">
              Native Execution. Global Liquidity.
            </h2>
            <BentoGrid />
          </section>
        </div>

        {/* ── PAYOUT CTA SECTION ── */}
        <div className="container">
          <section className="payout-section" id="payout-section">
            <h2 className="payout-section-title">
              Real Profits. Real Solana.
            </h2>
            <p className="payout-section-sub">
              Withdraw your share in USDT-SPL instantly. No withdrawal windows.
              No delays. Your profits hit your Solana wallet the moment you
              claim them.
            </p>
            <div className="payout-cards">
              <div className="payout-card">
                <span className="payout-card-value font-mono">$0.00</span>
                <span className="payout-card-label">Slippage</span>
              </div>
              <div className="payout-card">
                <span className="payout-card-value font-mono">90%</span>
                <span className="payout-card-label">Your Share</span>
              </div>
              <div className="payout-card">
                <span className="payout-card-value font-mono">Instant</span>
                <span className="payout-card-label">Settlement</span>
              </div>
            </div>
          </section>
        </div>

        {/* ── HOW IT WORKS ── */}
        <div className="container">
          <section className="features-section" id="how-it-works">
            <div className="section-label">How It Works</div>
            <h2 className="section-title">Three Steps to Start Winning.</h2>
            <div className="bento-grid">
              <div className="bento-card">
                <div className="bento-icon">
                  <span className="font-mono" style={{ fontSize: '1.25rem', fontWeight: 700 }}>01</span>
                </div>
                <h3 className="bento-card-title">Connect & Deploy</h3>
                <p className="bento-card-desc">
                  Connect your Solana wallet. Pay the{' '}
                  <span className="font-mono" style={{ color: 'var(--emerald)' }}>$100</span>{' '}
                  entry fee. Your funded account is provisioned in under 60 seconds.
                </p>
              </div>
              <div className="bento-card">
                <div className="bento-icon">
                  <span className="font-mono" style={{ fontSize: '1.25rem', fontWeight: 700 }}>02</span>
                </div>
                <h3 className="bento-card-title">Trade Live Markets</h3>
                <p className="bento-card-desc">
                  Execute trades on BTC, ETH, SOL, and XRP perpetuals with real CEX/DEX liquidity.{' '}
                  <span className="font-mono" style={{ color: 'var(--emerald)' }}>$2,000</span>{' '}
                  funded capital at your fingertips.
                </p>
              </div>
              <div className="bento-card">
                <div className="bento-icon">
                  <span className="font-mono" style={{ fontSize: '1.25rem', fontWeight: 700 }}>03</span>
                </div>
                <h3 className="bento-card-title">Claim Profits</h3>
                <p className="bento-card-desc">
                  Keep{' '}
                  <span className="font-mono" style={{ color: 'var(--emerald)' }}>90%</span>{' '}
                  of every dollar you earn. Withdraw your share in USDT-SPL directly to your Solana wallet — instantly.
                </p>
              </div>
            </div>
          </section>
        </div>

        {/* ── FOOTER ── */}
        <div className="container">
          <footer className="footer" id="footer">
            <div className="footer-inner">
              <div className="footer-brand">
                © {new Date().getFullYear()} SOTREUS. All rights reserved.
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
