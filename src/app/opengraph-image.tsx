import { ImageResponse } from 'next/og';

export const alt = 'SOTREUS launch tiers for funded crypto accounts';
export const size = {
  width: 1200,
  height: 630,
};
export const contentType = 'image/png';
export const dynamic = 'force-static';

export default function OpenGraphImage() {
  return new ImageResponse(
    (
      <div
        style={{
          display: 'flex',
          width: '100%',
          height: '100%',
          background:
            'radial-gradient(circle at top, rgba(52, 211, 153, 0.32), transparent 50%), linear-gradient(180deg, #020617 0%, #06111f 100%)',
          color: '#f8fafc',
          fontFamily: 'Inter, Arial, sans-serif',
          padding: '56px',
        }}
      >
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'space-between',
            width: '100%',
            border: '1px solid rgba(255,255,255,0.12)',
            borderRadius: '28px',
            padding: '42px 44px',
            background: 'rgba(15, 23, 42, 0.55)',
          }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '14px',
              color: '#34d399',
              fontSize: '22px',
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
            }}
          >
            <div
              style={{
                width: '12px',
                height: '12px',
                borderRadius: '999px',
                background: '#34d399',
                boxShadow: '0 0 20px rgba(52, 211, 153, 0.8)',
              }}
            />
            SOTREUS
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
            <div
              style={{
                fontSize: '72px',
                fontWeight: 800,
                lineHeight: 1.02,
                letterSpacing: '-0.04em',
                maxWidth: '820px',
              }}
            >
              Start Small. Trade Clean.
            </div>
            <div
              style={{
                fontSize: '30px',
                lineHeight: 1.45,
                color: 'rgba(248, 250, 252, 0.8)',
                maxWidth: '920px',
              }}
            >
              $99, $499, and $999 launch tiers for funded crypto accounts from
              $2,000 to $20,000.
            </div>
          </div>

          <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
            {['Wallet-first onboarding', 'Clear risk rules', 'Up to 90% trader share'].map(
              (tag) => (
                <div
                  key={tag}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    padding: '14px 18px',
                    borderRadius: '999px',
                    border: '1px solid rgba(52, 211, 153, 0.28)',
                    background: 'rgba(52, 211, 153, 0.09)',
                    color: '#d1fae5',
                    fontSize: '22px',
                  }}
                >
                  {tag}
                </div>
              )
            )}
          </div>
        </div>
      </div>
    ),
    size
  );
}
