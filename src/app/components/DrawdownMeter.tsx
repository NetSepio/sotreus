'use client';

import { useState, useEffect } from 'react';

interface DrawdownMeterProps {
  equity?: number;
  floor?: number;
  funded?: number;
}

export default function DrawdownMeter({
  equity: initialEquity = 2000,
  floor = 1900,
  funded = 2000,
}: DrawdownMeterProps) {
  const [equity, setEquity] = useState(initialEquity);

  // Simulate live equity fluctuation for demo
  useEffect(() => {
    const interval = setInterval(() => {
      setEquity((prev) => {
        const delta = (Math.random() - 0.48) * 8; // slight downward bias
        const next = prev + delta;
        // Clamp between floor - 10 and funded + 200
        return Math.max(floor - 10, Math.min(funded + 200, next));
      });
    }, 2000);

    return () => clearInterval(interval);
  }, [floor, funded]);

  const totalRange = funded - floor; // $100 range
  const distanceFromFloor = equity - floor;
  const percentage = Math.max(0, Math.min(100, (distanceFromFloor / totalRange) * 100));

  // Determine status
  const drawdownPercent = ((funded - equity) / funded) * 100;
  let status: 'safe' | 'warning' | 'danger';
  if (drawdownPercent >= 4) {
    status = 'danger';
  } else if (drawdownPercent >= 3) {
    status = 'warning';
  } else {
    status = 'safe';
  }

  const statusLabels = {
    safe: 'SAFE',
    warning: 'CAUTION',
    danger: 'CRITICAL',
  };

  const remaining = Math.max(0, equity - floor);

  return (
    <div className="drawdown-meter">
      <div className="drawdown-header">
        <span className="drawdown-title">Risk Gauge</span>
        <span className={`drawdown-status ${status}`}>
          <span
            className="live-dot"
            style={{
              background:
                status === 'safe'
                  ? 'var(--green-safe)'
                  : status === 'warning'
                  ? 'var(--yellow-warn)'
                  : 'var(--red-danger)',
            }}
          />
          {statusLabels[status]}
        </span>
      </div>

      <div className="drawdown-values">
        <span className="drawdown-equity">
          ${equity.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
        </span>
        <span className="drawdown-floor">
          Floor: ${floor.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
        </span>
      </div>

      <div className="drawdown-bar-track">
        <div
          className={`drawdown-bar-fill ${status}`}
          style={{ width: `${percentage}%` }}
        />
      </div>

      <div className="drawdown-remaining">
        <strong className="font-mono">
          ${remaining.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
        </strong>
        {' '}remaining before liquidation
      </div>
    </div>
  );
}
