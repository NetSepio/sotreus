'use client';

import { Zap, ShieldCheck, TrendingUp } from 'lucide-react';

const features = [
  {
    icon: Zap,
    title: 'Instant Provisioning',
    description:
      'Deploy capital in one click. Your funded account is live in under 60 seconds — no evaluations, no waiting periods, no sim trading.',
  },
  {
    icon: ShieldCheck,
    title: 'Zero Evaluation Phase',
    description:
      'Skip the multi-phase challenges. Trade live capital from day one with real market conditions and real CEX/DEX liquidity.',
  },
  {
    icon: TrendingUp,
    title: '90/10 Profit Split',
    description:
      'Keep 90% of every dollar you earn. Withdraw your share in USDT-SPL directly to your Solana wallet — instantly.',
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
