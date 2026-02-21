export default function Home() {
  return (
    <div style={{ position: "relative", minHeight: "100vh" }}>
      {/* Background Video & Overlay */}
      <div className="video-container">
        <video
          className="bg-video"
          src="https://d8j0ntlcm91z4.cloudfront.net/user_38xzZboKViGWJOttwIXH07lWA1P/hf_20260217_030345_246c0224-10a4-422c-b324-070b7c0eceda.mp4"
          autoPlay
          loop
          muted
          playsInline
        />
        <div className="video-overlay" />
      </div>

      {/* Main Content */}
      <div className="content-wrapper">
        {/* Navbar */}
        <nav className="navbar">
          <div className="nav-left">
            <a href="/" style={{ textDecoration: 'none', color: 'inherit' }}>
              <div className="nav-logo-text" style={{ width: '187px', height: '25px', display: 'flex', alignItems: 'center' }}>
                SOTREUS
              </div>
            </a>
            {/* 
            <div className="nav-links">
              <a href="#" className="nav-link">
                Get Started
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M6 9L12 15L18 9" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </a>
              <a href="#" className="nav-link">
                Developers
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M6 9L12 15L18 9" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </a>
              <a href="#" className="nav-link">
                Features
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M6 9L12 15L18 9" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </a>
              <a href="#" className="nav-link">
                Resources
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M6 9L12 15L18 9" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </a>
            </div>
            */}
          </div>
          <div className="nav-right nav-btn">
            <div className="btn-wrapper">
              <div className="btn-glow" />
              <button className="btn-inner">Join Waitlist</button>
            </div>
          </div>
        </nav>

        {/* Hero Content */}
        <div className="hero-content">
          <div className="hero-badge">
            <div className="badge-dot" />
            <span className="badge-text-dim">Securing the next node</span>
          </div>

          <div>
            <h1 className="hero-heading">Zero Trust. Absolute Resilience.</h1>
            <p className="hero-subtitle" style={{ marginTop: '24px', marginLeft: 'auto', marginRight: 'auto' }}>
              We are engineering a paradigm shift in decentralized security. The infrastructure is fortifying, and early access is on the horizon. Join the waitlist to secure your position.
            </p>
          </div>

          <div className="cta-btn-wrapper">
            <div className="btn-wrapper">
              <div className="btn-glow" />
              <button className="btn-inner">Join Waitlist</button>
            </div>
          </div>
        </div>

        {/* Footer */}
        <footer style={{
          width: '100%',
          padding: '24px 0',
          textAlign: 'center',
          borderTop: '1px solid rgba(255, 255, 255, 0.1)',
          marginTop: 'auto',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: '12px'
        }}>
          <p style={{
            margin: 0,
            fontSize: '13px',
            color: 'rgba(255, 255, 255, 0.5)',
            fontWeight: 400
          }}>
            SOTREUS is a product of <a href="https://netsepio.com" target="_blank" rel="noopener noreferrer" style={{ color: 'rgba(255, 255, 255, 0.8)', textDecoration: 'none', borderBottom: '1px solid rgba(255,255,255,0.3)' }}>NetSepio</a>
          </p>
          <div style={{ display: 'flex', gap: '16px', alignItems: 'center' }}>
            <a href="https://x.com/netsepio" target="_blank" rel="noopener noreferrer" className="social-link">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" xmlns="http://www.w3.org/2000/svg">
                <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
              </svg>
            </a>
            <a href="https://github.com/netsepio" target="_blank" rel="noopener noreferrer" className="social-link">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" xmlns="http://www.w3.org/2000/svg">
                <path fillRule="evenodd" clipRule="evenodd" d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z" />
              </svg>
            </a>
          </div>
        </footer>
      </div>
    </div>
  );
}
