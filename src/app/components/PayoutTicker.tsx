'use client';

import { motion } from 'framer-motion';

const highlights = [
  {
    badge: 'Tier',
    value: '$99',
    detail: '$2,000 launch account for first-time funded traders',
  },
  {
    badge: 'Tier',
    value: '$499',
    detail: '$10,000 account for traders with a repeatable setup',
  },
  {
    badge: 'Tier',
    value: '$999',
    detail: '$20,000 account for disciplined traders ready for more size',
  },
  {
    badge: 'Markets',
    value: 'BTC / ETH / SOL / XRP',
    detail: 'Launch with liquid crypto perpetuals before broadening the menu',
  },
  {
    badge: 'Rules',
    value: 'Clear loss floors',
    detail: 'Read the active rule set before checkout, not after a breach',
  },
  {
    badge: 'Payouts',
    value: 'Up to 90%',
    detail: 'Trader share should match the live plan and published policy',
  },
];

const doubledHighlights = [...highlights, ...highlights];

export default function PayoutTicker() {
  return (
    <div className="marquee-container">
      <motion.div
        className="marquee-track"
        animate={{ x: ['0%', '-50%'] }}
        transition={{
          x: {
            repeat: Infinity,
            repeatType: 'loop',
            duration: 30,
            ease: 'linear',
          },
        }}
      >
        {doubledHighlights.map((highlight, i) => (
          <div key={i} className="payout-item">
            <span className="payout-badge">{highlight.badge}</span>
            <span className="payout-amount">{highlight.value}</span>
            <span className="payout-wallet">{highlight.detail}</span>
          </div>
        ))}
      </motion.div>
    </div>
  );
}
