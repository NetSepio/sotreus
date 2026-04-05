'use client';

import { ShieldCheck, TrendingUp, Wallet } from 'lucide-react';

const features = [
  {
    icon: Wallet,
    title: 'Start Small, Scale When Ready',
    description:
      'Most prop firms force traders into larger fees or oversized account jumps. SOTREUS starts with smaller launch tiers so traders can build process before they size up.',
  },
  {
    icon: ShieldCheck,
    title: 'Wallet-First Crypto Flow',
    description:
      'Built for crypto-native traders who already live on-chain. Solana rails, wallet-based onboarding, and major crypto perpetuals keep the product aligned with real market behavior.',
  },
  {
    icon: TrendingUp,
    title: 'Rules Before Hype',
    description:
      'Readable loss limits, visible payout terms, and privacy language that is easy to review should matter as much as the headline numbers. Trust is part of the product.',
  },
];

export default function BentoGrid() {
  return (
    <div className="bento-grid">
      {features.map((feature, i) => {
        const Icon = feature.icon;
        return (
          <div key={i} className="bento-card" id={`feature-card-${i}`}>
            <div className="bento-icon">
              <Icon size={24} strokeWidth={2} />
            </div>
            <h3 className="bento-card-title">{feature.title}</h3>
            <p className="bento-card-desc">{feature.description}</p>
          </div>
        );
      })}
    </div>
  );
}
