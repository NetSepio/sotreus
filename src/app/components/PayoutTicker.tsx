'use client';

import { motion } from 'framer-motion';

const payouts = [
  { wallet: '7xK9...mP2q', amount: 847.20, time: '2m ago' },
  { wallet: 'Bv3R...kW8n', amount: 1263.50, time: '5m ago' },
  { wallet: '9fT4...xL7h', amount: 312.80, time: '8m ago' },
  { wallet: 'Hm6J...nQ1r', amount: 2150.00, time: '12m ago' },
  { wallet: '3pY8...aD5v', amount: 589.40, time: '15m ago' },
  { wallet: 'Kw2E...tR9m', amount: 1875.60, time: '18m ago' },
  { wallet: 'Qz5N...gF3c', amount: 423.10, time: '22m ago' },
  { wallet: '8bU1...yS6k', amount: 1034.90, time: '25m ago' },
  { wallet: 'Dx7V...wA4p', amount: 667.30, time: '30m ago' },
  { wallet: 'Fj9L...hC2x', amount: 1590.70, time: '34m ago' },
  { wallet: '2mG6...eN8t', amount: 945.00, time: '38m ago' },
  { wallet: 'Rk4W...iB7j', amount: 278.50, time: '42m ago' },
];

// Double the items for seamless loop
const doubledPayouts = [...payouts, ...payouts];

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
        {doubledPayouts.map((payout, i) => (
          <div key={i} className="payout-item">
            <span className="payout-badge">
              <span className="live-dot" style={{ width: 4, height: 4 }} />
              PAID
            </span>
            <span className="payout-wallet">{payout.wallet}</span>
            <span className="payout-amount">
              +${payout.amount.toLocaleString('en-US', { minimumFractionDigits: 2 })}
            </span>
            <span className="payout-time">{payout.time}</span>
          </div>
        ))}
      </motion.div>
    </div>
  );
}
