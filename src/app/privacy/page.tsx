import Link from 'next/link';
import type { Metadata } from 'next';

const sections = [
  {
    title: '1. Information SOTREUS may collect',
    body:
      'SOTREUS may collect information you provide directly and information generated through your use of the Services. This can include wallet addresses, payment details, account profile data, support requests, trading activity, device information, and usage logs.',
    bullets: [
      'Wallet and transaction data may be used to verify purchases, payouts, and account ownership.',
      'Trading and behavior data may be used to enforce risk rules, detect abuse, and improve the product.',
    ],
  },
  {
    title: '2. Why the information is used',
    body:
      'SOTREUS uses data to provision accounts, operate the platform, enforce account rules, prevent fraud, comply with legal obligations, communicate with traders, and improve product performance and support.',
    bullets: [
      'Some data is needed to keep accounts secure and to investigate suspicious activity.',
      'Some data is used to understand which pages, plans, and product flows are working.',
    ],
  },
  {
    title: '3. Cookies, analytics, and similar technologies',
    body:
      'SOTREUS may use cookies, pixels, local storage, analytics tooling, or similar technologies to remember preferences, measure performance, and understand traffic and conversion patterns.',
    bullets: [
      'Browser settings may allow you to limit some tracking technologies.',
      'Disabling certain technologies can affect account access or site functionality.',
    ],
  },
  {
    title: '4. Sharing and disclosure',
    body:
      'SOTREUS may share information with service providers, infrastructure vendors, analytics partners, payment or payout partners, compliance vendors, and authorities where required by law or to protect the platform.',
    bullets: [
      'Information may also be shared in connection with audits, fraud reviews, or a business transfer.',
      'SOTREUS does not need to sell personal data to operate the product.',
    ],
  },
  {
    title: '5. Data retention',
    body:
      'SOTREUS keeps information for as long as it is reasonably needed to run the Services, maintain records, resolve disputes, meet legal obligations, and enforce platform rules.',
    bullets: [
      'Retention periods may vary depending on the type of data and the purpose for which it was collected.',
      'Some records may be retained longer where required for compliance or fraud prevention.',
    ],
  },
  {
    title: '6. Security',
    body:
      'SOTREUS uses administrative, technical, and operational safeguards intended to protect account information. No method of storage or transmission is perfectly secure, so absolute security cannot be guaranteed.',
    bullets: [
      'You are responsible for protecting your wallet, device, and account credentials.',
      'If you believe your account or wallet has been compromised, contact the official SOTREUS support channel immediately.',
    ],
  },
  {
    title: '7. Your choices and rights',
    body:
      'Depending on your location, you may have rights to access, correct, delete, or object to certain uses of your information. SOTREUS may need to retain some data even after a request where the law or a legitimate business need allows it.',
    bullets: [
      'Identity verification may be required before rights requests are fulfilled.',
      'Some requests may be limited if fulfilling them would undermine security, fraud prevention, or legal compliance.',
    ],
  },
  {
    title: '8. Policy updates',
    body:
      'SOTREUS may update this Privacy Policy as the platform evolves. The current version becomes effective when posted on the website unless another effective date is stated.',
    bullets: [
      'Material updates should be reflected clearly on the site.',
      'Continued use of the Services after an update means you accept the revised Privacy Policy.',
    ],
  },
];

export const metadata: Metadata = {
  title: 'Privacy Policy',
  description:
    'Read how SOTREUS collects, uses, protects, and shares data related to funded accounts, wallets, analytics, payouts, and support.',
};

export default function PrivacyPage() {
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
            PRIVACY
          </div>
          <h1 className="legal-title">Privacy Policy</h1>
          <p className="legal-intro">
            This page explains the kinds of data SOTREUS may collect, why that
            data matters to the product, and how it may be used to operate,
            secure, and improve the platform.
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
          Privacy language should stay aligned with the actual tooling used in
          production. If SOTREUS adds new analytics, verification, payment, or
          support vendors, this page should be updated before or at launch.
        </div>
      </div>
    </main>
  );
}
