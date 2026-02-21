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
      </div>
    </div>
  );
}
